package com.athlete.order.infrastructure.projection;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.event.DomainEvent.OrderCancelled;
import com.athlete.order.domain.event.DomainEvent.OrderConfirmed;
import com.athlete.order.domain.event.DomainEvent.OrderPlaced;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.infrastructure.persistence.EventSerde;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Builds the {@code rm_order_summary} read model by consuming the event stream in global-position
 * order from a durable checkpoint (CQRS, ADR 0001). The read model holds no truth: it can be
 * dropped and rebuilt by resetting the checkpoint to 0 and replaying.
 */
@Component
public class OrderSummaryProjector {

    private static final String PROJECTION = "order_summary";

    private final JdbcTemplate jdbc;
    private final EventSerde serde;

    public OrderSummaryProjector(JdbcTemplate jdbc, EventSerde serde) {
        this.jdbc = jdbc;
        this.serde = serde;
    }

    @Scheduled(fixedDelayString = "${aoep.projection.poll-interval-ms:1000}")
    @Transactional
    public void project() {
        long checkpoint = currentCheckpoint();
        List<EventRow> rows = jdbc.query("""
                SELECT global_position, event_type, payload, occurred_at
                FROM events
                WHERE global_position > ?
                ORDER BY global_position
                LIMIT 500
                """,
                (rs, n) -> new EventRow(
                        rs.getLong("global_position"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("occurred_at").toInstant()),
                checkpoint);

        long lastPosition = checkpoint;
        for (EventRow row : rows) {
            apply(serde.deserialize(row.eventType(), row.payload()), row);
            lastPosition = row.globalPosition();
        }
        if (lastPosition > checkpoint) {
            saveCheckpoint(lastPosition);
        }
    }

    private void apply(DomainEvent event, EventRow row) {
        switch (event) {
            case OrderPlaced e -> jdbc.update("""
                    INSERT INTO rm_order_summary
                        (order_id, athlete_id, status, total_amount, currency, line_count,
                         placed_at, last_event_at, last_position)
                    VALUES (?, ?, 'AWAITING_CONFIRMATION', ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (order_id) DO NOTHING
                    """,
                    e.orderId().value(),
                    e.athleteId().value(),
                    e.total().amount(),
                    e.total().currency().getCurrencyCode(),
                    e.lines().size(),
                    Timestamp.from(e.occurredAt()),
                    Timestamp.from(row.occurredAt()),
                    row.globalPosition());
            case OrderConfirmed e -> updateStatus(e.orderId(), "CONFIRMED", row);
            case OrderCancelled e -> updateStatus(e.orderId(), "CANCELLED", row);
            default -> {
                // Other events do not affect the summary read model.
            }
        }
    }

    private void updateStatus(OrderId orderId, String status, EventRow row) {
        jdbc.update("""
                UPDATE rm_order_summary
                SET status = ?, last_event_at = ?, last_position = ?
                WHERE order_id = ?
                """,
                status, Timestamp.from(row.occurredAt()), row.globalPosition(), orderId.value());
    }

    private long currentCheckpoint() {
        Long position = jdbc.query(
                "SELECT last_position FROM projection_checkpoints WHERE projection_name = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                PROJECTION);
        return position == null ? 0L : position;
    }

    private void saveCheckpoint(long position) {
        jdbc.update("""
                INSERT INTO projection_checkpoints (projection_name, last_position, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (projection_name)
                DO UPDATE SET last_position = EXCLUDED.last_position, updated_at = now()
                """, PROJECTION, position);
    }

    private record EventRow(long globalPosition, String eventType, String payload, Instant occurredAt) {
    }
}
