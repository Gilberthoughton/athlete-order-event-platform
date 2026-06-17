package com.athlete.order.application;

import com.athlete.order.domain.model.OrderId;

/**
 * In-process application event published after an order is placed. The confirmation saga listens
 * for it after the placing transaction commits (ADR 0009). Distinct from a domain or integration
 * event — it is purely an internal trigger.
 */
public record OrderPlacedAppEvent(OrderId orderId) {
}
