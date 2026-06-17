package com.athlete.order.application.port;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.domain.model.OrderId;

import java.util.List;

/**
 * Stores integration events in the transactional outbox. Implementations MUST write within the
 * same transaction as the corresponding event-store append, so the two commit atomically and the
 * dual-write problem is eliminated (ADR 0002).
 */
public interface OutboxRepository {
    void append(OrderId orderId, List<IntegrationEvent> events);
}
