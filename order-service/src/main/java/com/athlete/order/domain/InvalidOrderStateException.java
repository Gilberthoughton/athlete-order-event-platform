package com.athlete.order.domain;

/** Raised when a command is invalid for the order's current (event-derived) state. */
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
