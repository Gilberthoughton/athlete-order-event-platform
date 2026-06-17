package com.athlete.order.domain.model;

import java.util.Objects;

/** Inventory reserved for an order line at a specific location. */
public record Allocation(Sku sku, int quantity, String locationId) {

    public Allocation {
        Objects.requireNonNull(sku, "sku is required");
        Objects.requireNonNull(locationId, "locationId is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("allocation quantity must be positive");
        }
    }
}
