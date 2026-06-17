package com.athlete.order.domain.model;

/** A positive count of units. */
public record Quantity(int value) {

    public Quantity {
        if (value <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, was " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }
}
