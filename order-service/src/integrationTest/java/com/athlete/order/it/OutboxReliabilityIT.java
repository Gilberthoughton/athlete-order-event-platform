package com.athlete.order.it;

import com.athlete.order.infrastructure.outbox.OutboxPollingPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reliability properties of the polling relay against real PostgreSQL + Kafka. The scheduled relay
 * is parked (huge poll interval) so the test drives publishing explicitly and deterministically.
 */
@SpringBootTest(properties = {
        "aoep.outbox.poll-interval-ms=3600000",
        "aoep.projection.poll-interval-ms=3600000"
})
class OutboxReliabilityIT extends AbstractIntegrationTest {

    @Autowired
    OutboxPollingPublisher publisher; // proxied bean: runs inside a transaction

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void successful_publish_marks_row_dispatched_and_emits_to_kafka() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertOutboxRow(orderId, eventId);

        publisher.publishPending();

        assertThat(dispatchedAt(eventId)).isNotNull();
        String value = awaitKafkaRecord("order.events",
                record -> orderId.toString().equals(record.key()), Duration.ofSeconds(15));
        assertThat(value).contains("OrderConfirmed");
    }

    @Test
    void failed_publish_leaves_row_undispatched_and_retryable() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertOutboxRow(orderId, eventId);

        // A relay pointed at an unreachable broker fails to publish.
        OutboxPollingPublisher broken = new OutboxPollingPublisher(jdbc, brokenKafkaTemplate());
        broken.publishPending();
        assertThat(dispatchedAt(eventId)).isNull(); // still pending -> retryable

        // A healthy relay then dispatches the same row.
        publisher.publishPending();
        assertThat(dispatchedAt(eventId)).isNotNull();
    }

    // ---- helpers ----

    private void insertOutboxRow(UUID orderId, UUID eventId) {
        String envelope = """
                {"eventId":"%s","eventType":"OrderConfirmed","schemaVersion":1,
                 "aggregateId":"%s","payload":{"orderId":{"value":"%s"}}}
                """.formatted(eventId, orderId, orderId);
        jdbc.update("""
                INSERT INTO outbox (event_id, aggregate_id, topic, event_type, schema_version,
                                    payload, correlation_id, created_at)
                VALUES (?, ?, 'order.events', 'OrderConfirmed', 1, ?::jsonb, ?, now())
                """, eventId, orderId, envelope, orderId);
    }

    private Timestamp dispatchedAt(UUID eventId) {
        return jdbc.queryForObject("SELECT dispatched_at FROM outbox WHERE event_id = ?",
                Timestamp.class, eventId);
    }

    private static KafkaTemplate<String, String> brokenKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"); // nothing listening
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
        props.put(ProducerConfig.RETRIES_CONFIG, 0);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
