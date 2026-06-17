package com.athlete.inventory.it;

import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Properties;

/**
 * Base for inventory-consumer integration tests. Real PostgreSQL and Kafka (Apache KRaft) run in
 * Testcontainers. Tests publish Avro events to Kafka with a raw producer wired to the same in-JVM
 * mock Schema Registry ({@code mock://aoep-it}) the consumer uses, exercising the consumer exactly
 * as the order-service would over the wire contract.
 */
@Testcontainers
@ActiveProfiles("it")
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

    protected static void send(String topic, String key, OrderIntegrationEvent event) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroSerializer");
        props.put("schema.registry.url", MOCK_REGISTRY);
        try (KafkaProducer<String, OrderIntegrationEvent> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, event));
            producer.flush();
        }
    }
}
