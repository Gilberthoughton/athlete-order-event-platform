package com.athlete.order.infrastructure.stub;

import com.athlete.order.application.port.PaymentGateway;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic stub standing in for an external payment provider so the confirmation saga's
 * happy path and decline/compensation path are both demonstrable without a real gateway. Orders
 * totaling at or above {@link #DECLINE_THRESHOLD} are declined; everything else is authorized.
 */
@Component
public class StubPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("5000.00");

    @Override
    public Result authorize(OrderId orderId, Money amount) {
        if (amount.amount().compareTo(DECLINE_THRESHOLD) >= 0) {
            return Result.declined("LIMIT_EXCEEDED");
        }
        String authorizationId = "AUTH-" + UUID.randomUUID();
        log.info("authorized payment {} for order {} amount {}", authorizationId, orderId, amount.amount());
        return Result.authorized(authorizationId);
    }

    @Override
    public void voidAuthorization(String authorizationId) {
        log.info("voided payment authorization {}", authorizationId);
    }
}
