package com.athlete.order.application;

import com.athlete.order.application.port.EventStore;
import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.model.OrderId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test double for {@link EventStore} that enforces the same optimistic-concurrency contract. */
class InMemoryEventStore implements EventStore {

    private final Map<UUID, List<DomainEvent>> streams = new HashMap<>();

    @Override
    public List<DomainEvent> load(OrderId orderId) {
        return new ArrayList<>(streams.getOrDefault(orderId.value(), List.of()));
    }

    @Override
    public void append(OrderId orderId, long expectedVersion, List<DomainEvent> newEvents) {
        List<DomainEvent> stream = streams.computeIfAbsent(orderId.value(), k -> new ArrayList<>());
        long currentVersion = stream.size() - 1L;
        if (currentVersion != expectedVersion) {
            throw new ConcurrencyConflictException(orderId, expectedVersion, null);
        }
        stream.addAll(newEvents);
    }

    int eventCount(OrderId orderId) {
        return streams.getOrDefault(orderId.value(), List.of()).size();
    }
}
