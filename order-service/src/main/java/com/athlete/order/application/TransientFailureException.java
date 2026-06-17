package com.athlete.order.application;

/**
 * Signals a retryable, transient failure from a downstream port (e.g. a timeout). The saga's
 * retry policy reacts to this type; declines and out-of-stock outcomes are normal results, not
 * exceptions, and are not retried.
 */
public class TransientFailureException extends RuntimeException {
    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
