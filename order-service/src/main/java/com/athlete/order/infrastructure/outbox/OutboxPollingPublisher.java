package com.athlete.order.infrastructure.outbox;

import com.athlete.order.infrastructure.observability.OrderMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling implementation of the outbox relay (ADR 0002). Each tick claims a batch of undispatched
 * rows with {@code FOR UPDATE SKIP LOCKED} (so multiple instances can poll without contention),
 * publishes them to Kafka keyed by aggregate id, and stamps {@code dispatched_at}.
 *
 * <p>Delivery is at-least-once: if a send fails we stop the batch and leave the row undispatched
 * for the next tick, which preserves per-aggregate ordering. Debezium CDC is the documented
 * production upgrade behind this same relay seam.
 */
@Component
public class OutboxPollingPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingPublisher.class);

    private static final String CLAIM_SQL = """
            SELECT id, aggregate_id, topic, payload
            FROM outbox
            WHERE dispatched_at IS NULL
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT 100
            """;

    private static final String MARK_SQL = "UPDATE outbox SET dispatched_at = now() WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OrderMetrics metrics;

    public OutboxPollingPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, OrderMetrics metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${aoep.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> batch = jdbc.query(CLAIM_SQL, (rs, rowNum) -> new OutboxRow(
                rs.getLong("id"),
                rs.getString("aggregate_id"),
                rs.getString("topic"),
                rs.getString("payload")));

        for (OutboxRow row : batch) {
            try {
                kafka.send(row.topic(), row.aggregateId(), row.payload()).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("relay interrupted at outbox id {}; retrying next tick", row.id());
                return;
            } catch (Exception e) {
                log.warn("relay send failed for outbox id {}; retrying next tick", row.id(), e);
                return; // stop the batch to preserve per-aggregate ordering
            }
            jdbc.update(MARK_SQL, row.id());
            metrics.outboxPublished();
        }
    }

    private record OutboxRow(long id, String aggregateId, String topic, String payload) {
    }
}
