package com.athlete.order.infrastructure.observability;

import org.slf4j.MDC;

/**
 * The correlation id for the work in flight on this thread.
 *
 * <p>The HTTP filter puts the id on the SLF4J {@link MDC} so it appears on every log line; the
 * outbox reads it back when writing an integration-event envelope, so the same id travels to the
 * consumer and one order's activity can be followed across both services. The key lives here rather
 * than on the filter so the write path does not have to depend on the web layer to find it.
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    /** The current correlation id, or {@code null} outside a correlated unit of work. */
    public static String currentOrNull() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }
}
