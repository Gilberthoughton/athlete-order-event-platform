package com.athlete.order.api.dto;

/** Optional body for cancelling an order; reason defaults when omitted. */
public record CancelOrderRequest(String reason) {

    public String reasonOrDefault() {
        return (reason == null || reason.isBlank()) ? "CSR_REQUEST" : reason;
    }
}
