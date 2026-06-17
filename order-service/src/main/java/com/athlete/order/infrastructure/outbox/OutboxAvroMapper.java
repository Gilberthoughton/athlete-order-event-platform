package com.athlete.order.infrastructure.outbox;

import com.athlete.order.contracts.avro.OrderEventType;
import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Maps a stored outbox envelope (a flat JSON document written inside the order transaction) into
 * the Avro {@link OrderIntegrationEvent} wire record. Avro serialization and Schema Registry
 * interaction happen here, in the asynchronous relay — never in the order transaction — so a
 * registry outage cannot block order processing (ADR 0002, ADR 0004).
 */
@Component
public class OutboxAvroMapper {

    private final ObjectMapper mapper;

    public OutboxAvroMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public OrderIntegrationEvent toAvro(String envelopeJson) {
        JsonNode node;
        try {
            node = mapper.readTree(envelopeJson);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse outbox envelope", e);
        }
        return OrderIntegrationEvent.newBuilder()
                .setEventId(node.get("eventId").asText())
                .setEventType(eventType(node.get("eventType").asText()))
                .setAggregateId(node.get("aggregateId").asText())
                .setCorrelationId(node.get("correlationId").asText())
                .setOccurredAt(node.get("occurredAt").asLong())
                .setSchemaVersion(node.path("schemaVersion").asInt(1))
                .setAthleteId(text(node, "athleteId"))
                .setTotalAmount(text(node, "totalAmount"))
                .setCurrency(text(node, "currency"))
                .setReasonCode(text(node, "reasonCode"))
                .build();
    }

    private static OrderEventType eventType(String type) {
        return switch (type) {
            case "OrderConfirmed" -> OrderEventType.ORDER_CONFIRMED;
            case "OrderCancelled" -> OrderEventType.ORDER_CANCELLED;
            default -> throw new IllegalArgumentException("unknown integration event type: " + type);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
