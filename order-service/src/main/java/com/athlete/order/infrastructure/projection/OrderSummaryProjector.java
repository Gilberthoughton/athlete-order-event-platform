package com.athlete.order.infrastructure.projection;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.event.DomainEvent.OrderCancelled;
import com.athlete.order.domain.event.DomainEvent.OrderConfirmed;
import com.athlete.order.domain.event.DomainEvent.OrderPlaced;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.infrastructure.observability.OrderMetrics;
import com.athlete.order.infrastructure.persistence.EventSerde;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
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

    private static final Logger log = LoggerFactory.getLogger(OrderSummaryProjector.class);

    private final JdbcTemplate jdbc;
    private final EventSerde serde;
    private final OrderMetrics metrics;
    private final Duration gapGrace;

    public OrderSummaryProjector(
            JdbcTemplate jdbc,
            EventSerde serde,
            OrderMetrics metrics,
            @Value("${aoep.projection.gap-grace-ms:30000}") long gapGraceMs) {
        this.jdbc = jdbc;
        this.serde = serde;
        this.metrics = metrics;
        // Must exceed the longest write transaction, so a position is only declared abandoned
        // once no in-flight transaction could still commit into it.
        this.gapGrace = Duration.ofMillis(gapGraceMs);
    }

    @Scheduled(fixedDelayString = "${aoep.projection.poll-interval-ms:1000}")
    @Transactional
    public void project() {
        Checkpoint checkpoint = currentCheckpoint();
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
                checkpoint.position());

        if (rows.isEmpty()) {
            // Nothing visible beyond the checkpoint, so no gap is observable either.
            if (checkpoint.hasPendingGap()) {
                saveCheckpoint(checkpoint.position(), null, null);
            }
            return;
        }

        if (checkpoint.position() == 0) {
            // Consuming from position 0 with events present is a full (re)build of the read model.
            metrics.projectionRebuild();
        }

        // Apply only the contiguous run starting at checkpoint+1. `global_position` is assigned
        // when a row is inserted rather than when its transaction commits, so a missing position
        // may still belong to a transaction that has not committed yet. Stepping over it would
        // skip that event permanently once the checkpoint moved past it.
        long expected = checkpoint.position() + 1;
        long lastApplied = checkpoint.position();
        Long gapAt = null;
        for (EventRow row : rows) {
            if (row.globalPosition() != expected) {
                gapAt = expected;
                break;
            }
            apply(serde.deserialize(row.eventType(), row.payload()), row);
            metrics.projectionEventApplied();
            lastApplied = row.globalPosition();
            expected++;
        }

        if (gapAt == null) {
            if (lastApplied > checkpoint.position()) {
                saveCheckpoint(lastApplied, null, null);
            }
            return;
        }

        if (checkpoint.isPendingGapExpired(gapAt, gapGrace, Instant.now())) {
            // The position was consumed by a transaction that rolled back, so it is never
            // coming. Step over it, otherwise the projection stalls forever.
            log.warn("Projection '{}' skipping position {} — no event committed there within {}",
                    PROJECTION, gapAt, gapGrace);
            saveCheckpoint(gapAt, null, null);
            return;
        }

        Instant firstSeen = checkpoint.isSameGap(gapAt) ? checkpoint.gapFirstSeen() : Instant.now();
        saveCheckpoint(lastApplied, gapAt, firstSeen);
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

    private Checkpoint currentCheckpoint() {
        Checkpoint checkpoint = jdbc.query("""
                SELECT last_position, pending_gap_position, pending_gap_first_seen
                FROM projection_checkpoints
                WHERE projection_name = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    long position = rs.getLong("last_position");
                    long gap = rs.getLong("pending_gap_position");
                    Long gapPosition = rs.wasNull() ? null : gap;
                    Timestamp firstSeen = rs.getTimestamp("pending_gap_first_seen");
                    return new Checkpoint(position, gapPosition,
                            firstSeen == null ? null : firstSeen.toInstant());
                },
                PROJECTION);
        return checkpoint == null ? new Checkpoint(0L, null, null) : checkpoint;
    }

    private void saveCheckpoint(long position, Long gapPosition, Instant gapFirstSeen) {
        jdbc.update("""
                INSERT INTO projection_checkpoints
                    (projection_name, last_position, updated_at,
                     pending_gap_position, pending_gap_first_seen)
                VALUES (?, ?, now(), ?, ?)
                ON CONFLICT (projection_name)
                DO UPDATE SET last_position = EXCLUDED.last_position,
                              updated_at = now(),
                              pending_gap_position = EXCLUDED.pending_gap_position,
                              pending_gap_first_seen = EXCLUDED.pending_gap_first_seen
                """,
                PROJECTION, position, gapPosition,
                gapFirstSeen == null ? null : Timestamp.from(gapFirstSeen));
    }

    private record EventRow(long globalPosition, String eventType, String payload, Instant occurredAt) {
    }

    /**
     * Projection progress: the last contiguous position applied, plus the position the stream is
     * currently waiting on (if any) and when that wait began.
     */
    private record Checkpoint(long position, Long gapPosition, Instant gapFirstSeen) {

        boolean hasPendingGap() {
            return gapPosition != null;
        }

        boolean isSameGap(long candidate) {
            return gapPosition != null && gapPosition == candidate && gapFirstSeen != null;
        }

        boolean isPendingGapExpired(long candidate, Duration grace, Instant now) {
            return isSameGap(candidate) && !now.isBefore(gapFirstSeen.plus(grace));
        }
    }
}
