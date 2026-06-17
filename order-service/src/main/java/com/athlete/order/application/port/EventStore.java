package com.athlete.order.application.port;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.model.OrderId;

import java.util.List;

/**
 * The authoritative, append-only store of domain events (ADR 0001). Implementations enforce
 * optimistic concurrency via the {@code expectedVersion} contract (ADR 0005).
 */
public interface EventStore {

    /** Loads an aggregate's full event history in sequence order (empty if it does not exist). */
    List<DomainEvent> load(OrderId orderId);

    /**
     * Appends new events after {@code expectedVersion} (the sequence number of the last event the
     * caller observed; -1 for a new stream). Throws if another writer has advanced the stream.
     */
    void append(OrderId orderId, long expectedVersion, List<DomainEvent> newEvents);
}
