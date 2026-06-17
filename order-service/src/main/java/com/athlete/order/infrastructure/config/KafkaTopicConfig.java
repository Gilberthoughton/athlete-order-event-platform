package com.athlete.order.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the order events topic so it is created on startup with an explicit partition count.
 * Per-aggregate ordering relies on keying by orderId across these partitions (ADR 0007).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
