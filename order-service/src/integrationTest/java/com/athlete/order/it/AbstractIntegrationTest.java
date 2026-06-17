package com.athlete.order.it;

import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Base for order-service integration tests. Real PostgreSQL and Kafka run in Testcontainers; the
 * containers are static and shared across all IT classes, so they start once per run. Postgres is
 * wired via {@code @ServiceConnection}; Kafka (Apache KRaft) via {@code @DynamicPropertySource}.
 *
 * <p>Avro events are serialized against an in-JVM mock Schema Registry ({@code mock://aoep-it},
 * configured in application-it.yml). The verification consumer below uses the same mock scope, so
 * it shares the registered schemas with the application within the test JVM.
 */
@Testcontainers
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static final String MOCK_REGISTRY = "mock://aoep-it";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** A fresh Avro consumer with manual assignment (no consumer-group coordination). */
    protected static KafkaConsumer<String, OrderIntegrationEvent> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroDeserializer");
        props.put("schema.registry.url", MOCK_REGISTRY);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(props);
    }

    /**
     * Polls the topic until a record matches, returning the deserialized Avro event, or fails after
     * the timeout. Uses manual partition assignment + seekToBeginning so the read is deterministic
     * and does not depend on consumer-group coordination latency.
     */
    protected static OrderIntegrationEvent awaitKafkaRecord(String topic,
                                                            Predicate<ConsumerRecord<String, OrderIntegrationEvent>> matcher,
                                                            Duration timeout) {
        try (KafkaConsumer<String, OrderIntegrationEvent> consumer = newConsumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + timeout.toNanos();
            int seen = 0;
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, OrderIntegrationEvent> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, OrderIntegrationEvent> record : records) {
                    seen++;
                    if (matcher.test(record)) {
                        return record.value();
                    }
                }
            }
            throw new AssertionError("no matching record on topic '" + topic + "' within " + timeout
                    + " (partitions=" + partitions.size() + ", recordsSeen=" + seen + ")");
        }
    }
}
