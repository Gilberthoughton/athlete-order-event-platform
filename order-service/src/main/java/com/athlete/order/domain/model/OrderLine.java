package com.athlete.order.domain.model;

import java.util.Objects;

/** A single SKU + quantity + unit price within an order. */
public record OrderLine(Sku sku, Quantity quantity, Money unitPrice, FulfillmentType fulfillmentType) {

    public OrderLine {
        Objects.requireNonNull(sku, "sku is required");
        Objects.requireNonNull(quantity, "quantity is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        Objects.requireNonNull(fulfillmentType, "fulfillmentType is required");
    }

    /** Extended price for this line (unit price × quantity). */
    public Money lineTotal() {
        return unitPrice.multipliedBy(quantity.value());
    }
}
