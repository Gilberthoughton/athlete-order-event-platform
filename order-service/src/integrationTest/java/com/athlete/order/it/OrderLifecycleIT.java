package com.athlete.order.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full producer-side path against real infrastructure: place an order over REST, then verify the
 * domain events land in PostgreSQL, the integration event is written to the outbox and dispatched
 * by the polling relay, the record actually appears on Kafka, and the read-model projection is
 * updated. The downstream consume + idempotency half of the path is covered by the
 * inventory-consumer module's {@code ConsumerIT}, which meets this one at the Kafka contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void order_flows_from_rest_through_postgres_outbox_kafka_to_projection() {
        Map<String, Object> request = Map.of(
                "athleteId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of(
                        "sku", "SHOE-NIKE-PEGASUS-10",
                        "quantity", 1,
                        "unitPriceAmount", 129.99,
                        "currency", "USD",
                        "fulfillmentType", "SHIP")));

        // 1. Place the order through the REST boundary; the saga confirms it synchronously.
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/orders", request, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        UUID orderId = UUID.fromString(body.get("orderId").asText());
        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");

        // 2. Domain events are persisted to PostgreSQL in sequence.
        List<String> eventTypes = jdbc.queryForList(
                "SELECT event_type FROM events WHERE aggregate_id = ? ORDER BY sequence_no",
                String.class, orderId);
        assertThat(eventTypes)
                .containsExactly("OrderPlaced", "PaymentAuthorized", "InventoryAllocated", "OrderConfirmed");

        // 3. The integration event is written to the outbox and dispatched by the polling relay.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer dispatched = jdbc.queryForObject("""
                    SELECT count(*) FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'OrderConfirmed' AND dispatched_at IS NOT NULL
                    """, Integer.class, orderId);
            assertThat(dispatched).isEqualTo(1);
        });

        // 4. The record actually lands on Kafka, keyed by orderId.
        String published = awaitKafkaRecord("order.events",
                record -> orderId.toString().equals(record.key())
                        && record.value().contains("OrderConfirmed"),
                Duration.ofSeconds(20));
        assertThat(published).contains(orderId.toString()).contains("OrderConfirmed");

        // 5. The read-model projection is updated to CONFIRMED.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM rm_order_summary WHERE order_id = ?", String.class, orderId);
            assertThat(status).isEqualTo("CONFIRMED");
        });
    }
}
