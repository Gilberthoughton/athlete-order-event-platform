package com.athlete.order.it;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.FulfillmentType;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.Quantity;
import com.athlete.order.domain.model.Sku;
import com.athlete.order.infrastructure.persistence.EventSerde;
import com.athlete.order.infrastructure.projection.OrderSummaryProjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection must never permanently skip a committed event.
 *
 * <p>{@code events.global_position} is a sequence value assigned when a row is INSERTed, not when
 * its transaction commits. A transaction holding position N can therefore become visible after one
 * holding N+1. A projector that checkpointed the highest position it had seen would store N+1 and
 * never read N again — and because the read model row is only created by {@code OrderPlaced}, a
 * skipped OrderPlaced makes the order disappear from {@code rm_order_summary} entirely: the later
 * status UPDATE matches no row and fails silently.
 *
 * <p>These tests drive that interleaving directly with two connections.
 */
@SpringBootTest
class ProjectionGapIT extends AbstractIntegrationTest {

    private static final String INSERT_EVENT = """
            INSERT INTO events
                (event_id, aggregate_id, aggregate_type, sequence_no, event_type,
                 schema_version, payload, correlation_id, occurred_at)
            VALUES (?, ?, 'Order', ?, ?, 1, ?::jsonb, ?, ?)
            RETURNING global_position
            """;

    @Autowired
    private OrderSummaryProjector projector;

    @Autowired
    private EventSerde serde;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetStream() {
        jdbc.update("DELETE FROM rm_order_summary");
        jdbc.update("DELETE FROM projection_checkpoints");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM events");
    }

    @Test
    void does_not_skip_an_event_whose_transaction_commits_after_a_later_one() throws Exception {
        OrderId earlier = OrderId.newId();
        OrderId later = OrderId.newId();

        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);

            // The "slow" transaction takes the lower position but has not committed yet.
            long earlierPosition = insertOrderPlaced(slow, earlier);
            startProjectionAt(earlierPosition - 1);

            // A second transaction takes the next position and commits immediately, so only the
            // higher position is visible to the projector.
            long laterPosition;
            try (Connection fast = dataSource.getConnection()) {
                laterPosition = insertOrderPlaced(fast, later);
            }
            assertThat(laterPosition).isEqualTo(earlierPosition + 1);

            projector.project();

            // Nothing may be applied: stepping over the invisible position would strand it.
            assertThat(summaryIds()).isEmpty();
            assertThat(checkpointPosition()).isEqualTo(earlierPosition - 1);
            assertThat(pendingGapPosition()).isEqualTo(earlierPosition);

            slow.commit();
        }

        projector.project();

        // Both orders are projected, in position order, once the gap fills.
        assertThat(summaryIds()).containsExactlyInAnyOrder(earlier.value(), later.value());
        assertThat(pendingGapPosition()).isNull();
    }

    @Test
    void skips_a_position_abandoned_by_a_rolled_back_transaction() throws Exception {
        OrderId rolledBack = OrderId.newId();
        OrderId committed = OrderId.newId();

        long abandonedPosition;
        try (Connection doomed = dataSource.getConnection()) {
            doomed.setAutoCommit(false);
            abandonedPosition = insertOrderPlaced(doomed, rolledBack);
            startProjectionAt(abandonedPosition - 1);
            doomed.rollback();
        }

        try (Connection ok = dataSource.getConnection()) {
            insertOrderPlaced(ok, committed);
        }

        // First pass records the gap; the position may still belong to an in-flight transaction.
        projector.project();
        assertThat(summaryIds()).isEmpty();
        assertThat(pendingGapPosition()).isEqualTo(abandonedPosition);

        // Age the observation past the grace period. The sequence value was consumed by a
        // transaction that rolled back, so nothing will ever commit there.
        jdbc.update("""
                UPDATE projection_checkpoints
                SET pending_gap_first_seen = now() - interval '1 hour'
                WHERE projection_name = 'order_summary'
                """);

        projector.project();   // steps over the abandoned position
        projector.project();   // applies the committed event that followed it

        assertThat(summaryIds()).containsExactly(committed.value());
        assertThat(pendingGapPosition()).isNull();
    }

    // --- helpers ---------------------------------------------------------------------------

    private long insertOrderPlaced(Connection connection, OrderId orderId) throws SQLException {
        OrderLine line = new OrderLine(
                new Sku("SHOE-1"), Quantity.of(1), Money.of("50.00", "USD"), FulfillmentType.SHIP);
        DomainEvent event = new DomainEvent.OrderPlaced(
                orderId, new AthleteId(UUID.randomUUID()), List.of(line),
                Money.of("50.00", "USD"), Instant.now());

        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, orderId.value());
            statement.setLong(3, 0L);
            statement.setString(4, serde.eventType(event));
            statement.setString(5, serde.serialize(event));
            statement.setObject(6, UUID.randomUUID());
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Seed the checkpoint so the projector expects {@code position + 1} next. */
    private void startProjectionAt(long position) {
        jdbc.update("""
                INSERT INTO projection_checkpoints (projection_name, last_position)
                VALUES ('order_summary', ?)
                ON CONFLICT (projection_name) DO UPDATE SET last_position = EXCLUDED.last_position
                """, position);
    }

    private List<UUID> summaryIds() {
        return jdbc.queryForList("SELECT order_id FROM rm_order_summary", UUID.class);
    }

    private long checkpointPosition() {
        return jdbc.queryForObject(
                "SELECT last_position FROM projection_checkpoints WHERE projection_name = 'order_summary'",
                Long.class);
    }

    private Long pendingGapPosition() {
        return jdbc.queryForObject(
                "SELECT pending_gap_position FROM projection_checkpoints WHERE projection_name = 'order_summary'",
                Long.class);
    }
}
