package com.athlete.order.application;

import com.athlete.order.domain.model.OrderId;

/** Raised when an append fails because another writer advanced the aggregate first (ADR 0005). */
public class ConcurrencyConflictException extends RuntimeException {
    public ConcurrencyConflictException(OrderId orderId, long expectedVersion, Throwable cause) {
        super("concurrent modification of order %s at expected version %d".formatted(orderId, expectedVersion), cause);
    }
}
