package com.athlete.inventory.it;

import com.athlete.order.contracts.avro.OrderEventType;
import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the downstream half of the path against real Kafka + PostgreSQL: an integration event
 * published to {@code order.events} is consumed, the fulfillment read model is updated, and a
 * duplicate delivery (same eventId) is ignored — proving consumer idempotency end to end.
 */
@SpringBootTest
class ConsumerIT extends AbstractIntegrationTest {

    private static final String TOPIC = "order.events";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void confirmed_event_updates_read_model_and_duplicate_delivery_is_ignored() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        send(TOPIC, orderId.toString(), confirmed(eventId, orderId, "129.99"));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo("CONFIRMED"));
        assertThat(amountOf(orderId)).isEqualByComparingTo("129.99");

        // Redeliver the same eventId with a different amount; dedup must ignore it.
        send(TOPIC, orderId.toString(), confirmed(eventId, orderId, "999.99"));
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(amountOf(orderId)).isEqualByComparingTo("129.99"));
    }

    @Test
    void cancelled_event_marks_order_cancelled() {
        UUID orderId = UUID.randomUUID();

        send(TOPIC, orderId.toString(), cancelled(UUID.randomUUID(), orderId, "PAYMENT_DECLINED:LIMIT_EXCEEDED"));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo("CANCELLED"));
    }

    // ---- helpers ----

    private String statusOf(UUID orderId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM fulfillment_order WHERE order_id = ?", String.class, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal amountOf(UUID orderId) {
        List<BigDecimal> rows = jdbc.queryForList(
                "SELECT total_amount FROM fulfillment_order WHERE order_id = ?", BigDecimal.class, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static OrderIntegrationEvent confirmed(UUID eventId, UUID orderId, String amount) {
        return OrderIntegrationEvent.newBuilder()
                .setEventId(eventId.toString())
                .setEventType(OrderEventType.ORDER_CONFIRMED)
                .setAggregateId(orderId.toString())
                .setCorrelationId(orderId.toString())
                .setOccurredAt(Instant.now().toEpochMilli())
                .setSchemaVersion(1)
                .setAthleteId(UUID.randomUUID().toString())
                .setTotalAmount(amount)
                .setCurrency("USD")
                .build();
    }

    private static OrderIntegrationEvent cancelled(UUID eventId, UUID orderId, String reason) {
        return OrderIntegrationEvent.newBuilder()
                .setEventId(eventId.toString())
                .setEventType(OrderEventType.ORDER_CANCELLED)
                .setAggregateId(orderId.toString())
                .setCorrelationId(orderId.toString())
                .setOccurredAt(Instant.now().toEpochMilli())
                .setSchemaVersion(1)
                .setReasonCode(reason)
                .build();
    }
}
