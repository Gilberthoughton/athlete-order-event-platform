package com.athlete.order.contracts;

import com.athlete.order.contracts.avro.OrderEventType;
import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the integration event through the real Confluent Avro serializer/deserializer against
 * an in-JVM mock Schema Registry (no running registry needed). Exercises schema registration,
 * the wire format (magic byte + schema id + Avro binary), and specific-record deserialization.
 */
class OrderIntegrationEventAvroTest {

    private static final String TOPIC = "order.events";
    private static final Map<String, Object> CONFIG = Map.of(
            "schema.registry.url", "mock://aoep-unit",
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

    @Test
    void serializes_and_deserializes_an_order_confirmed_event() {
        OrderIntegrationEvent event = OrderIntegrationEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(OrderEventType.ORDER_CONFIRMED)
                .setAggregateId(UUID.randomUUID().toString())
                .setCorrelationId(UUID.randomUUID().toString())
                .setOccurredAt(Instant.now().toEpochMilli())
                .setSchemaVersion(1)
                .setAthleteId(UUID.randomUUID().toString())
                .setTotalAmount("129.99")
                .setCurrency("USD")
                .build();

        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer();
             KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer()) {
            serializer.configure(CONFIG, false);
            deserializer.configure(CONFIG, false);

            byte[] bytes = serializer.serialize(TOPIC, event);
            Object decoded = deserializer.deserialize(TOPIC, bytes);

            assertThat(decoded).isInstanceOf(OrderIntegrationEvent.class);
            OrderIntegrationEvent result = (OrderIntegrationEvent) decoded;
            assertThat(result.getEventType()).isEqualTo(OrderEventType.ORDER_CONFIRMED);
            assertThat(result.getTotalAmount()).isEqualTo("129.99");
            assertThat(result.getEventId()).isEqualTo(event.getEventId());
        }
    }
}
