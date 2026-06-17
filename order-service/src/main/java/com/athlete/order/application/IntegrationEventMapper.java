package com.athlete.order.application;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.domain.Order;
import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.event.DomainEvent.OrderCancelled;
import com.athlete.order.domain.event.DomainEvent.OrderConfirmed;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Anti-corruption translation from internal domain events to the public integration contract
 * (ADR 0003). Only a subset of domain events surface publicly, and they are enriched from the
 * aggregate so consumers receive a meaningful, denormalized event.
 */
@Component
public class IntegrationEventMapper {

    public List<IntegrationEvent> map(Order order, List<DomainEvent> newEvents) {
        List<IntegrationEvent> result = new ArrayList<>();
        for (DomainEvent event : newEvents) {
            switch (event) {
                case OrderConfirmed e -> result.add(new IntegrationEvent.OrderConfirmed(
                        order.id(), order.athleteId(), order.total(), e.occurredAt()));
                case OrderCancelled e -> result.add(new IntegrationEvent.OrderCancelled(
                        order.id(), e.reasonCode(), e.occurredAt()));
                default -> {
                    // Internal-only events (placed, payment/inventory steps) are not published.
                }
            }
        }
        return result;
    }
}
