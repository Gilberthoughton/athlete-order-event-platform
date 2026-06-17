package com.athlete.order.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Central registry of the platform's domain metrics, so meter names and descriptions live in one
 * place. Exposed at {@code /actuator/prometheus}; see docs/architecture/overview.md and the
 * Grafana dashboard under monitoring/.
 */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;
    private final Counter ordersPlaced;
    private final Counter ordersConfirmed;
    private final Counter ordersCancelled;
    private final Counter outboxPublished;
    private final Counter sagaRetries;
    private final Counter projectionEventsApplied;
    private final Counter projectionRebuilds;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ordersPlaced = Counter.builder("aoep.orders.placed")
                .description("Orders placed").register(registry);
        this.ordersConfirmed = Counter.builder("aoep.orders.confirmed")
                .description("Orders confirmed (payment authorized + inventory allocated)").register(registry);
        this.ordersCancelled = Counter.builder("aoep.orders.cancelled")
                .description("Orders cancelled (declined payment, failed allocation, or CSR request)").register(registry);
        this.outboxPublished = Counter.builder("aoep.outbox.published")
                .description("Integration events published from the outbox to Kafka").register(registry);
        this.sagaRetries = Counter.builder("aoep.saga.retries")
                .description("Confirmation-saga transient-failure retries").register(registry);
        this.projectionEventsApplied = Counter.builder("aoep.projection.events.applied")
                .description("Events applied to the read-model projection").register(registry);
        this.projectionRebuilds = Counter.builder("aoep.projection.rebuilds")
                .description("Projection (re)builds replayed from position 0").register(registry);
    }

    public void orderPlaced() {
        ordersPlaced.increment();
    }

    public void orderConfirmed() {
        ordersConfirmed.increment();
    }

    public void orderCancelled() {
        ordersCancelled.increment();
    }

    public void outboxPublished() {
        outboxPublished.increment();
    }

    public void sagaRetry() {
        sagaRetries.increment();
    }

    public void projectionEventApplied() {
        projectionEventsApplied.increment();
    }

    public void projectionRebuild() {
        projectionRebuilds.increment();
    }

    /** Records a terminal saga outcome: {@code confirmed}, {@code cancelled}, or {@code failed}. */
    public void sagaCompleted(String outcome) {
        registry.counter("aoep.saga.completed", "outcome", outcome).increment();
    }
}
