package com.athlete.order.api.dto;

import com.athlete.order.application.PlaceOrderCommand;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.OrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Request body for placing an order. */
public record PlaceOrderRequest(
        @NotNull UUID athleteId,
        @NotEmpty @Valid List<LineRequest> lines) {

    public PlaceOrderCommand toCommand() {
        List<OrderLine> orderLines = lines.stream().map(LineRequest::toOrderLine).toList();
        return new PlaceOrderCommand(new AthleteId(athleteId), orderLines);
    }
}
