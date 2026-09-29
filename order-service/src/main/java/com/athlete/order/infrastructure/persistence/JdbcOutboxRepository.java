package com.athlete.order.infrastructure.persistence;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.application.port.OutboxRepository;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.infrastructure.config.KafkaTopics;
import com.athlete.order.infrastructure.observability.CorrelationContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes integration events to the transactional outbox (ADR 0002). Called within the same
 * transaction as the event-store append, so the two commit atomically. Each row stores the full
 * self-describing JSON envelope the relay will publish verbatim, keyed by aggregate id.
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String INSERT_SQL = """
            INSERT INTO outbox
                (event_id, aggregate_id, topic, event_type, schema_version, payload,
                 correlation_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcOutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void append(OrderId orderId, List<IntegrationEvent> events) {
        Instant now = Instant.now();
        for (IntegrationEvent event : events) {
            UUID eventId = UUID.randomUUID();
            // The id that identifies this business flow in the logs. Falls back to the orderId when
            // the write happens outside a correlated unit of work (e.g. a background task).
            // correlation_id is a UUID column, so this must be bound as a UUID, not a String.
            UUID correlationId = currentCorrelationId(orderId);
            jdbc.update(INSERT_SQL,
                    eventId,
                    orderId.value(),
                    KafkaTopics.ORDER_EVENTS,
                    event.eventType(),
                    event.schemaVersion(),
                    envelope(eventId, orderId, event, now, correlationId.toString()),
                    correlationId,
                    Timestamp.from(now));
        }
    }

    /**
     * The correlation id established for the work in flight, or the order id when there is none
     * (or it is not a UUID, which the persisted column requires).
     */
    private static UUID currentCorrelationId(OrderId orderId) {
        String current = CorrelationContext.currentOrNull();
        if (current == null) {
            return orderId.value();
        }
        try {
            return UUID.fromString(current);
        } catch (IllegalArgumentException notAUuid) {
            return orderId.value();
        }
    }

    /**
     * Writes a flat envelope (metadata + type-specific fields) that maps 1:1 to the Avro wire
     * record. The relay translates this to {@link com.athlete.order.contracts.avro.OrderIntegrationEvent}
     * at publish time, keeping Avro/registry concerns out of the order transaction.
     */
    private String envelope(UUID eventId, OrderId orderId, IntegrationEvent event, Instant fallbackTime,
                            String correlationId) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", event.eventType());
        root.put("schemaVersion", event.schemaVersion());
        root.put("aggregateId", orderId.value().toString());
        root.put("correlationId", correlationId);
        switch (event) {
            case IntegrationEvent.OrderConfirmed e -> {
                root.put("occurredAt", e.confirmedAt().toEpochMilli());
                root.put("athleteId", e.athleteId().value().toString());
                root.put("totalAmount", e.total().amount().toPlainString());
                root.put("currency", e.total().currency().getCurrencyCode());
            }
            case IntegrationEvent.OrderCancelled e -> {
                root.put("occurredAt", e.cancelledAt().toEpochMilli());
                root.put("reasonCode", e.reasonCode());
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize integration event envelope", e);
        }
    }
}
