package com.athlete.order.application;

import com.athlete.order.domain.model.OrderId;

/** Raised when a command or query targets an order that does not exist. */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(OrderId orderId) {
        super("order not found: " + orderId);
    }
}
