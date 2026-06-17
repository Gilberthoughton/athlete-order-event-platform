package com.athlete.order.application;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.application.port.OutboxRepository;
import com.athlete.order.domain.model.OrderId;

import java.util.ArrayList;
import java.util.List;

/** Test double for {@link OutboxRepository} that records what would be published. */
class RecordingOutbox implements OutboxRepository {

    private final List<IntegrationEvent> published = new ArrayList<>();

    @Override
    public void append(OrderId orderId, List<IntegrationEvent> events) {
        published.addAll(events);
    }

    List<IntegrationEvent> published() {
        return published;
    }
}
