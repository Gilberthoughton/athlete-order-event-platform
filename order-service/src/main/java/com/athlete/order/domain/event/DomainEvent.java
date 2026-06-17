package com.athlete.order.domain.event;

import com.athlete.order.domain.model.Allocation;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;

import java.time.Instant;
import java.util.List;

/**
 * Internal domain events emitted by the {@code Order} aggregate. These are the source of truth
 * (persisted to the event store) and are intentionally distinct from the public integration
 * events published to Kafka — see ADR 0003. Past-tense names; immutable records.
 */
public sealed interface DomainEvent
        permits DomainEvent.OrderPlaced,
                DomainEvent.PaymentAuthorized,
                DomainEvent.PaymentAuthorizationDeclined,
                DomainEvent.InventoryAllocated,
                DomainEvent.InventoryAllocationFailed,
                DomainEvent.OrderConfirmed,
                DomainEvent.OrderCancelled {

    OrderId orderId();

    Instant occurredAt();

    record OrderPlaced(OrderId orderId, AthleteId athleteId, List<OrderLine> lines, Money total,
                       Instant occurredAt) implements DomainEvent {}

    record PaymentAuthorized(OrderId orderId, String authorizationId, Money amount,
                             Instant occurredAt) implements DomainEvent {}

    record PaymentAuthorizationDeclined(OrderId orderId, String reasonCode,
                                        Instant occurredAt) implements DomainEvent {}

    record InventoryAllocated(OrderId orderId, List<Allocation> allocations,
                              Instant occurredAt) implements DomainEvent {}

    record InventoryAllocationFailed(OrderId orderId, String reasonCode,
                                     Instant occurredAt) implements DomainEvent {}

    record OrderConfirmed(OrderId orderId, Instant occurredAt) implements DomainEvent {}

    record OrderCancelled(OrderId orderId, String reasonCode, Instant occurredAt) implements DomainEvent {}
}
