package com.athlete.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the consumer applies an event exactly once under at-least-once (duplicate) delivery,
 * and that it handles both confirmed and cancelled events — using fakes, no Kafka or DB.
 */
class OrderEventsConsumerTest {

    private FakeProcessedEventStore processed;
    private FakeFulfillmentStore fulfillment;
    private OrderEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        processed = new FakeProcessedEventStore();
        fulfillment = new FakeFulfillmentStore();
        consumer = new OrderEventsConsumer(processed, fulfillment,
                new ConsumerMetrics(new SimpleMeterRegistry()), new ObjectMapper());
    }

    @Test
    void duplicate_delivery_is_processed_only_once() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String message = confirmedEnvelope(eventId, orderId, "129.99", "USD");

        consumer.onMessage(message);
        consumer.onMessage(message); // redelivery

        assertThat(fulfillment.confirmedCount()).isEqualTo(1);
        assertThat(fulfillment.statusOf(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void cancelled_event_marks_the_order_cancelled() {
        UUID orderId = UUID.randomUUID();
        String message = cancelledEnvelope(UUID.randomUUID(), orderId, "PAYMENT_DECLINED:LIMIT_EXCEEDED");

        consumer.onMessage(message);

        assertThat(fulfillment.statusOf(orderId)).isEqualTo("CANCELLED");
    }

    private static String confirmedEnvelope(UUID eventId, UUID orderId, String amount, String currency) {
        return """
                {"eventId":"%s","eventType":"OrderConfirmed","aggregateId":"%s",
                 "payload":{"total":{"amount":%s,"currency":"%s"}}}
                """.formatted(eventId, orderId, amount, currency);
    }

    private static String cancelledEnvelope(UUID eventId, UUID orderId, String reason) {
        return """
                {"eventId":"%s","eventType":"OrderCancelled","aggregateId":"%s",
                 "payload":{"reasonCode":"%s"}}
                """.formatted(eventId, orderId, reason);
    }

    // ---- fakes ----

    private static final class FakeProcessedEventStore implements ProcessedEventStore {
        private final Set<UUID> seen = new HashSet<>();

        @Override
        public boolean markProcessed(UUID eventId) {
            return seen.add(eventId);
        }
    }

    private static final class FakeFulfillmentStore implements FulfillmentStore {
        private final List<UUID> confirmed = new ArrayList<>();
        private final java.util.Map<UUID, String> status = new java.util.HashMap<>();

        @Override
        public void recordConfirmed(UUID orderId, BigDecimal totalAmount, String currency) {
            confirmed.add(orderId);
            status.put(orderId, "CONFIRMED");
        }

        @Override
        public void recordCancelled(UUID orderId, String reasonCode) {
            status.put(orderId, "CANCELLED");
        }

        @Override
        public List<FulfillmentView> findAll() {
            return List.of();
        }

        int confirmedCount() {
            return confirmed.size();
        }

        String statusOf(UUID orderId) {
            return status.get(orderId);
        }
    }
}
