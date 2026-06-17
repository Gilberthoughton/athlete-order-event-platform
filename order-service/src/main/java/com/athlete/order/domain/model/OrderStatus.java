package com.athlete.order.domain.model;

/**
 * Lifecycle status of an order, derived by folding its event stream.
 * Phase 1 models the path to confirmation; shipping/delivery/return states arrive in Phase 2.
 */
public enum OrderStatus {
    /** Placed, awaiting payment authorization and inventory allocation. */
    AWAITING_CONFIRMATION,
    /** Payment authorized and inventory allocated — a firm commitment. */
    CONFIRMED,
    /** Terminated before fulfillment (declined payment, failed allocation, or CSR cancellation). */
    CANCELLED
}
