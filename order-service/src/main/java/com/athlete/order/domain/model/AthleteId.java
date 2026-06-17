package com.athlete.order.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identity of the athlete (customer) who owns an order. */
public record AthleteId(UUID value) {

    public AthleteId {
        Objects.requireNonNull(value, "AthleteId value is required");
    }

    public static AthleteId of(String value) {
        return new AthleteId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
