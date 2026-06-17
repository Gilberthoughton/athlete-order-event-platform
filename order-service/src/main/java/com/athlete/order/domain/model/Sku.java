package com.athlete.order.domain.model;

/** Stock Keeping Unit — identifies a sellable product variant. */
public record Sku(String value) {

    public Sku {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sku value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
