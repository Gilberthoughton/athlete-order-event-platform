package com.athlete.order.application.contract;

import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;

import java.time.Instant;

/**
 * Public integration events — the contract published to Kafka and consumed by other contexts
 * (ADR 0003). Deliberately coarse-grained and versioned; distinct from internal domain events.
 * Serialized as a self-describing JSON envelope in the outbox; Avro + Schema Registry is the
 * documented target (ADR 0004).
 */
public sealed interface IntegrationEvent
        permits IntegrationEvent.OrderConfirmed, IntegrationEvent.OrderCancelled {

    String eventType();

    int schemaVersion();

    OrderId orderId();

    record OrderConfirmed(OrderId orderId, AthleteId athleteId, Money total, Instant confirmedAt)
            implements IntegrationEvent {
        @Override
        public String eventType() {
            return "OrderConfirmed";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }
    }

    record OrderCancelled(OrderId orderId, String reasonCode, Instant cancelledAt)
            implements IntegrationEvent {
        @Override
        public String eventType() {
            return "OrderCancelled";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }
    }
}
