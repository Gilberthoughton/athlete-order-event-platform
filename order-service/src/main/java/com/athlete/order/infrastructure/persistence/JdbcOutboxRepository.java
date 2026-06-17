package com.athlete.order.infrastructure.persistence;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.application.port.OutboxRepository;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.infrastructure.config.KafkaTopics;
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
            jdbc.update(INSERT_SQL,
                    eventId,
                    orderId.value(),
                    KafkaTopics.ORDER_EVENTS,
                    event.eventType(),
                    event.schemaVersion(),
                    envelope(eventId, orderId, event, now),
                    orderId.value(),
                    Timestamp.from(now));
        }
    }

    private String envelope(UUID eventId, OrderId orderId, IntegrationEvent event, Instant occurredAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", event.eventType());
        root.put("schemaVersion", event.schemaVersion());
        root.put("aggregateId", orderId.value().toString());
        root.put("occurredAt", occurredAt.toString());
        root.put("correlationId", orderId.value().toString());
        root.set("payload", mapper.valueToTree(event));
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize integration event envelope", e);
        }
    }
}
