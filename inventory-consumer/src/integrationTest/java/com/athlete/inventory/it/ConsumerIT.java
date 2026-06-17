package com.athlete.inventory.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
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

        send(TOPIC, orderId.toString(), confirmedEnvelope(eventId, orderId, "129.99"));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo("CONFIRMED"));
        assertThat(amountOf(orderId)).isEqualByComparingTo("129.99");

        // Redeliver the same eventId with a different amount; dedup must ignore it.
        send(TOPIC, orderId.toString(), confirmedEnvelope(eventId, orderId, "999.99"));
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(amountOf(orderId)).isEqualByComparingTo("129.99"));
    }

    @Test
    void cancelled_event_marks_order_cancelled() {
        UUID orderId = UUID.randomUUID();

        send(TOPIC, orderId.toString(), cancelledEnvelope(UUID.randomUUID(), orderId, "PAYMENT_DECLINED:LIMIT_EXCEEDED"));

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

    private static String confirmedEnvelope(UUID eventId, UUID orderId, String amount) {
        return """
                {"eventId":"%s","eventType":"OrderConfirmed","schemaVersion":1,"aggregateId":"%s",
                 "payload":{"orderId":{"value":"%s"},"total":{"amount":%s,"currency":"USD"},
                 "confirmedAt":"2026-06-16T00:00:00Z"}}
                """.formatted(eventId, orderId, orderId, amount);
    }

    private static String cancelledEnvelope(UUID eventId, UUID orderId, String reason) {
        return """
                {"eventId":"%s","eventType":"OrderCancelled","schemaVersion":1,"aggregateId":"%s",
                 "payload":{"orderId":{"value":"%s"},"reasonCode":"%s","cancelledAt":"2026-06-16T00:00:00Z"}}
                """.formatted(eventId, orderId, orderId, reason);
    }
}
