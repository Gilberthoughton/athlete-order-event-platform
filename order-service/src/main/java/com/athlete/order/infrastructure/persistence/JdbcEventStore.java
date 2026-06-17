package com.athlete.order.infrastructure.persistence;

import com.athlete.order.application.ConcurrencyConflictException;
import com.athlete.order.application.port.EventStore;
import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.model.OrderId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Append-only event store backed by PostgreSQL (ADR 0001). Optimistic concurrency is enforced by
 * the {@code (aggregate_id, sequence_no)} unique constraint (ADR 0005): a losing concurrent append
 * surfaces as a {@link DuplicateKeyException}, translated to {@link ConcurrencyConflictException}.
 */
@Repository
public class JdbcEventStore implements EventStore {

    private static final String AGGREGATE_TYPE = "Order";

    private static final String INSERT_SQL = """
            INSERT INTO events
                (event_id, aggregate_id, aggregate_type, sequence_no, event_type,
                 schema_version, payload, correlation_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private static final String LOAD_SQL = """
            SELECT event_type, payload
            FROM events
            WHERE aggregate_id = ?
            ORDER BY sequence_no
            """;

    private final JdbcTemplate jdbc;
    private final EventSerde serde;

    public JdbcEventStore(JdbcTemplate jdbc, EventSerde serde) {
        this.jdbc = jdbc;
        this.serde = serde;
    }

    @Override
    public List<DomainEvent> load(OrderId orderId) {
        return jdbc.query(LOAD_SQL,
                (rs, rowNum) -> serde.deserialize(rs.getString("event_type"), rs.getString("payload")),
                orderId.value());
    }

    @Override
    public void append(OrderId orderId, long expectedVersion, List<DomainEvent> newEvents) {
        long sequenceNo = expectedVersion;
        try {
            for (DomainEvent event : newEvents) {
                sequenceNo++;
                jdbc.update(INSERT_SQL,
                        UUID.randomUUID(),
                        orderId.value(),
                        AGGREGATE_TYPE,
                        sequenceNo,
                        serde.eventType(event),
                        1,
                        serde.serialize(event),
                        orderId.value(),               // correlation_id: groups all events of this order
                        Timestamp.from(event.occurredAt()));
            }
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflictException(orderId, expectedVersion, e);
        }
    }
}
