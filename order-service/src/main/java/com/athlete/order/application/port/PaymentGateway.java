package com.athlete.order.application.port;

import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;

/**
 * Port to the external payment provider, driven by the confirmation saga (ADR 0009). A declined
 * authorization is a normal {@link Result}; transport problems are signalled by throwing
 * {@link com.athlete.order.application.TransientFailureException} so the saga can retry.
 */
public interface PaymentGateway {

    Result authorize(OrderId orderId, Money amount);

    /** Compensating action: release a previously authorized hold. */
    void voidAuthorization(String authorizationId);

    record Result(boolean declined, String authorizationId, String reasonCode) {

        public static Result authorized(String authorizationId) {
            return new Result(false, authorizationId, null);
        }

        public static Result declined(String reasonCode) {
            return new Result(true, null, reasonCode);
        }
    }
}
