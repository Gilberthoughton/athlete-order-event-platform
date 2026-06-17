package com.athlete.order.api.dto;

import com.athlete.order.domain.Order;

import java.math.BigDecimal;
import java.util.UUID;

/** Response view of an order's current (event-derived) state. */
public record OrderResponse(
        UUID orderId,
        String status,
        BigDecimal totalAmount,
        String currency,
        int lineCount,
        boolean paymentAuthorized,
        boolean inventoryAllocated) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id().value(),
                order.status().name(),
                order.total().amount(),
                order.total().currency().getCurrencyCode(),
                order.lines().size(),
                order.paymentAuthorized(),
                order.inventoryAllocated());
    }
}
