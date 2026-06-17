package com.athlete.order.api.dto;

import com.athlete.order.domain.model.FulfillmentType;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.Quantity;
import com.athlete.order.domain.model.Sku;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Currency;

/** A single requested order line. */
public record LineRequest(
        @NotBlank String sku,
        @Positive int quantity,
        @NotNull @Positive BigDecimal unitPriceAmount,
        @NotBlank String currency,
        @NotNull FulfillmentType fulfillmentType) {

    public OrderLine toOrderLine() {
        return new OrderLine(
                new Sku(sku),
                Quantity.of(quantity),
                Money.of(unitPriceAmount, Currency.getInstance(currency)),
                fulfillmentType);
    }
}
