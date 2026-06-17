package com.athlete.order.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identity of an Order aggregate. */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "OrderId value is required");
    }

    public static OrderId newId() {
        return new OrderId(UUID.randomUUID());
    }

    public static OrderId of(String value) {
        return new OrderId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
