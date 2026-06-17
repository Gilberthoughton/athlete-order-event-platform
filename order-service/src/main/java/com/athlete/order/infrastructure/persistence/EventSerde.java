package com.athlete.order.infrastructure.persistence;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.event.DomainEvent.InventoryAllocated;
import com.athlete.order.domain.event.DomainEvent.InventoryAllocationFailed;
import com.athlete.order.domain.event.DomainEvent.OrderCancelled;
import com.athlete.order.domain.event.DomainEvent.OrderConfirmed;
import com.athlete.order.domain.event.DomainEvent.OrderPlaced;
import com.athlete.order.domain.event.DomainEvent.PaymentAuthorizationDeclined;
import com.athlete.order.domain.event.DomainEvent.PaymentAuthorized;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Serializes domain events to/from JSON for the event store. The concrete type is recorded in the
 * {@code event_type} column, so deserialization is an explicit type lookup rather than embedded
 * polymorphic type metadata — keeping the stored payload clean and the mapping auditable.
 */
@Component
public class EventSerde {

    private final ObjectMapper mapper;

    public EventSerde(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String eventType(DomainEvent event) {
        return event.getClass().getSimpleName();
    }

    public String serialize(DomainEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event " + eventType(event), e);
        }
    }

    public DomainEvent deserialize(String eventType, String json) {
        try {
            return mapper.readValue(json, classFor(eventType));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize event " + eventType, e);
        }
    }

    private Class<? extends DomainEvent> classFor(String eventType) {
        return switch (eventType) {
            case "OrderPlaced" -> OrderPlaced.class;
            case "PaymentAuthorized" -> PaymentAuthorized.class;
            case "PaymentAuthorizationDeclined" -> PaymentAuthorizationDeclined.class;
            case "InventoryAllocated" -> InventoryAllocated.class;
            case "InventoryAllocationFailed" -> InventoryAllocationFailed.class;
            case "OrderConfirmed" -> OrderConfirmed.class;
            case "OrderCancelled" -> OrderCancelled.class;
            default -> throw new IllegalArgumentException("unknown event type: " + eventType);
        };
    }
}
