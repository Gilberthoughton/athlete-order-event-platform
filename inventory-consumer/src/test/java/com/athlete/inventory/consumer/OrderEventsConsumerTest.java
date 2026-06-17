package com.athlete.inventory.consumer;

import com.athlete.order.contracts.avro.OrderEventType;
import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the consumer applies an Avro event exactly once under at-least-once (duplicate) delivery
 * and handles both confirmed and cancelled events — using fakes, no Kafka, DB, or registry.
 */
class OrderEventsConsumerTest {

    private FakeProcessedEventStore processed;
    private FakeFulfillmentStore fulfillment;
    private OrderEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        processed = new FakeProcessedEventStore();
        fulfillment = new FakeFulfillmentStore();
        consumer = new OrderEventsConsumer(processed, fulfillment, new ConsumerMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void duplicate_delivery_is_processed_only_once() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OrderIntegrationEvent event = confirmed(eventId, orderId, "129.99");

        consumer.onMessage(event);
        consumer.onMessage(event); // redelivery

        assertThat(fulfillment.confirmedCount()).isEqualTo(1);
        assertThat(fulfillment.statusOf(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void cancelled_event_marks_the_order_cancelled() {
        UUID orderId = UUID.randomUUID();

        consumer.onMessage(cancelled(UUID.randomUUID(), orderId, "PAYMENT_DECLINED:LIMIT_EXCEEDED"));

        assertThat(fulfillment.statusOf(orderId)).isEqualTo("CANCELLED");
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
        private final Map<UUID, String> status = new HashMap<>();

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
