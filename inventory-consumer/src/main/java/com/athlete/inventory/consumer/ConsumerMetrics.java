package com.athlete.inventory.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics for the downstream consumer, exposed at {@code /actuator/prometheus}: events received,
 * duplicates ignored (idempotency), and events applied to the read model — each tagged by type.
 */
@Component
public class ConsumerMetrics {

    private final MeterRegistry registry;

    public ConsumerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void received(String eventType) {
        registry.counter("aoep.consumer.events.received", "type", eventType).increment();
    }

    public void duplicateIgnored(String eventType) {
        registry.counter("aoep.consumer.events.duplicate", "type", eventType).increment();
    }

    public void applied(String eventType) {
        registry.counter("aoep.consumer.events.applied", "type", eventType).increment();
    }
}
