package com.athlete.order.application;

import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.OrderLine;

import java.util.List;

/** Application command to place a new order. */
public record PlaceOrderCommand(AthleteId athleteId, List<OrderLine> lines) {
    public PlaceOrderCommand {
        lines = List.copyOf(lines);
    }
}
