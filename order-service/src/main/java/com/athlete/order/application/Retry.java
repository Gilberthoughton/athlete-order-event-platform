package com.athlete.order.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Minimal bounded-retry helper for transient downstream failures. Only
 * {@link TransientFailureException} is retried; all other exceptions propagate immediately.
 * Kept dependency-free on purpose; a production system would use Resilience4j/Spring Retry with
 * jittered backoff and circuit breaking.
 */
public final class Retry {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);

    private Retry() {
    }

    public static <T> T withRetries(int maxAttempts, Supplier<T> action) {
        TransientFailureException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (TransientFailureException e) {
                last = e;
                log.warn("transient failure on attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(50L * attempt);
                }
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientFailureException("retry interrupted", e);
        }
    }
}
