package com.athlete.order.application;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.application.port.EventStore;
import com.athlete.order.application.port.OutboxRepository;
import com.athlete.order.domain.Order;
import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.model.Allocation;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Application service that orchestrates the load → decide → persist cycle for the Order aggregate.
 *
 * <p>Each mutating method runs in a single transaction in which the event-store append and the
 * outbox write commit together (ADR 0002). Reads are served by rehydrating the aggregate; the
 * query-side read model is built separately by the projection (CQRS).
 */
@Service
public class OrderApplicationService {

    private final EventStore eventStore;
    private final OutboxRepository outbox;
    private final IntegrationEventMapper integrationEventMapper;
    private final ApplicationEventPublisher applicationEvents;

    public OrderApplicationService(EventStore eventStore,
                                   OutboxRepository outbox,
                                   IntegrationEventMapper integrationEventMapper,
                                   ApplicationEventPublisher applicationEvents) {
        this.eventStore = eventStore;
        this.outbox = outbox;
        this.integrationEventMapper = integrationEventMapper;
        this.applicationEvents = applicationEvents;
    }

    @Transactional
    public OrderId placeOrder(PlaceOrderCommand command) {
        OrderId id = OrderId.newId();
        Order order = Order.place(id, command.athleteId(), command.lines());
        persist(order);
        applicationEvents.publishEvent(new OrderPlacedAppEvent(id));
        return id;
    }

    @Transactional
    public void recordPaymentAuthorized(OrderId orderId, String authorizationId, Money amount) {
        Order order = load(orderId);
        order.authorizePayment(authorizationId, amount);
        persist(order);
    }

    @Transactional
    public void recordPaymentDeclined(OrderId orderId, String reasonCode) {
        Order order = load(orderId);
        order.declinePayment(reasonCode);
        persist(order);
    }

    @Transactional
    public void recordInventoryAllocated(OrderId orderId, List<Allocation> allocations) {
        Order order = load(orderId);
        order.allocateInventory(allocations);
        persist(order);
    }

    @Transactional
    public void recordInventoryAllocationFailed(OrderId orderId, String reasonCode) {
        Order order = load(orderId);
        order.failInventoryAllocation(reasonCode);
        persist(order);
    }

    @Transactional
    public void cancel(OrderId orderId, String reasonCode) {
        Order order = load(orderId);
        order.cancel(reasonCode);
        persist(order);
    }

    @Transactional(readOnly = true)
    public Optional<Order> find(OrderId orderId) {
        List<DomainEvent> history = eventStore.load(orderId);
        return history.isEmpty() ? Optional.empty() : Optional.of(Order.rehydrate(history));
    }

    private Order load(OrderId orderId) {
        List<DomainEvent> history = eventStore.load(orderId);
        if (history.isEmpty()) {
            throw new OrderNotFoundException(orderId);
        }
        return Order.rehydrate(history);
    }

    private void persist(Order order) {
        List<DomainEvent> newEvents = order.pendingEvents();
        if (newEvents.isEmpty()) {
            return;
        }
        eventStore.append(order.id(), order.version(), newEvents);
        List<IntegrationEvent> integrationEvents = integrationEventMapper.map(order, newEvents);
        if (!integrationEvents.isEmpty()) {
            outbox.append(order.id(), integrationEvents);
        }
        order.markEventsCommitted();
    }
}
