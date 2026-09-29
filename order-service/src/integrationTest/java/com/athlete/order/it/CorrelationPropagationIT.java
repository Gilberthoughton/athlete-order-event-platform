package com.athlete.order.it;

import com.athlete.order.api.CorrelationIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * The correlation id a caller supplies must reach the integration event, so one order's activity can
 * be followed from the inbound request through to the downstream consumer's logs.
 *
 * <p>The outbox previously wrote the {@code orderId} into the envelope's {@code correlationId}
 * field, ignoring the id the filter had put on the MDC. The two services therefore logged different
 * values for the same order and nothing actually correlated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationPropagationIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_inbound_correlation_id_reaches_the_outbox_row_and_envelope() {
        String correlationId = UUID.randomUUID().toString();

        HttpHeaders headers = new HttpHeaders();
        headers.add(CorrelationIdFilter.HEADER, correlationId);
        headers.add("Content-Type", "application/json");
        Map<String, Object> request = Map.of(
                "athleteId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of(
                        "sku", "SHOE-NIKE-PEGASUS-10",
                        "quantity", 1,
                        "unitPriceAmount", 129.99,
                        "currency", "USD",
                        "fulfillmentType", "SHIP")));

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/orders", HttpMethod.POST, new HttpEntity<>(request, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .as("the caller's correlation id is echoed back")
                .isEqualTo(correlationId);

        UUID orderId = UUID.fromString(response.getBody().get("orderId").asText());

        // The saga writes the integration event after the response commits, so wait for the row.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForList(
                    "SELECT correlation_id FROM outbox WHERE aggregate_id = ?", String.class, orderId))
                    .as("every integration event carries the caller's correlation id, not the orderId")
                    .isNotEmpty()
                    .allMatch(correlationId::equals);

            assertThat(jdbc.queryForList(
                    "SELECT payload ->> 'correlationId' FROM outbox WHERE aggregate_id = ?",
                    String.class, orderId))
                    .as("the published envelope carries it too, so the consumer adopts the same id")
                    .isNotEmpty()
                    .allMatch(correlationId::equals);
        });
    }

    @Test
    void a_non_uuid_correlation_header_is_replaced_rather_than_failing_the_write() {
        // correlation_id is a UUID column on both events and outbox, so an arbitrary header value
        // cannot be persisted. The filter substitutes a generated id and echoes the substitute back.
        HttpHeaders headers = new HttpHeaders();
        headers.add(CorrelationIdFilter.HEADER, "not-a-uuid");
        headers.add("Content-Type", "application/json");
        Map<String, Object> request = Map.of(
                "athleteId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of(
                        "sku", "SHOE-NIKE-PEGASUS-10",
                        "quantity", 1,
                        "unitPriceAmount", 129.99,
                        "currency", "USD",
                        "fulfillmentType", "SHIP")));

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/orders", HttpMethod.POST, new HttpEntity<>(request, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String echoed = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(echoed).isNotEqualTo("not-a-uuid");
        assertThat(UUID.fromString(echoed)).isNotNull();

        UUID orderId = UUID.fromString(response.getBody().get("orderId").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForList(
                        "SELECT correlation_id FROM outbox WHERE aggregate_id = ?", String.class, orderId))
                        .as("the order still completes and its events are written")
                        .isNotEmpty()
                        .allMatch(echoed::equals));
    }
}
