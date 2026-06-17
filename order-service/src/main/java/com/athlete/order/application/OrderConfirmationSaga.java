package com.athlete.order.application;

import com.athlete.order.application.port.InventoryAllocator;
import com.athlete.order.application.port.PaymentGateway;
import com.athlete.order.domain.Order;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderStatus;
import com.athlete.order.infrastructure.observability.OrderMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Process manager that drives a placed order to confirmation (ADR 0009): authorize payment, then
 * allocate inventory, then confirm — with retry on transient failures and compensation when a
 * later step fails after an earlier one succeeded.
 *
 * <p>Triggered after the placing transaction commits. It is re-entrant and idempotent: it reloads
 * fresh state on each run and the aggregate's commands are no-ops once already applied, so a
 * redelivered trigger or a reconciliation re-drive cannot double-confirm.
 */
@Component
public class OrderConfirmationSaga {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationSaga.class);
    private static final int MAX_ATTEMPTS = 3;

    private final OrderApplicationService orders;
    private final PaymentGateway paymentGateway;
    private final InventoryAllocator inventoryAllocator;
    private final OrderMetrics metrics;

    public OrderConfirmationSaga(OrderApplicationService orders,
                                 PaymentGateway paymentGateway,
                                 InventoryAllocator inventoryAllocator,
                                 OrderMetrics metrics) {
        this.orders = orders;
        this.paymentGateway = paymentGateway;
        this.inventoryAllocator = inventoryAllocator;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedAppEvent event) {
        try {
            drive(event.orderId());
        } catch (RuntimeException e) {
            // The order stays AWAITING_CONFIRMATION and is recovered by the reconciliation sweep;
            // never let a saga failure propagate out of the after-commit phase.
            metrics.sagaCompleted("failed");
            log.error("saga failed for order {}; will be reconciled", event.orderId(), e);
        }
    }

    /** Drives the order toward confirmation. Safe to call repeatedly. */
    public void drive(OrderId orderId) {
        Order order = orders.find(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.status() != OrderStatus.AWAITING_CONFIRMATION) {
            return; // already terminal (confirmed or cancelled)
        }

        String authorizationId = order.paymentAuthorizationId().orElse(null);

        if (!order.paymentAuthorized()) {
            PaymentGateway.Result payment = Retry.withRetries(MAX_ATTEMPTS,
                    () -> paymentGateway.authorize(orderId, order.total()), metrics::sagaRetry);
            if (payment.declined()) {
                log.info("payment declined for order {}: {}", orderId, payment.reasonCode());
                orders.recordPaymentDeclined(orderId, payment.reasonCode());
                metrics.sagaCompleted("cancelled");
                return;
            }
            authorizationId = payment.authorizationId();
            orders.recordPaymentAuthorized(orderId, authorizationId, order.total());
        }

        if (!order.inventoryAllocated()) {
            InventoryAllocator.Result allocation = Retry.withRetries(MAX_ATTEMPTS,
                    () -> inventoryAllocator.allocate(orderId, order.lines()), metrics::sagaRetry);
            if (allocation.failed()) {
                log.info("inventory allocation failed for order {}: {}", orderId, allocation.reasonCode());
                compensatePayment(authorizationId);
                orders.recordInventoryAllocationFailed(orderId, allocation.reasonCode());
                metrics.sagaCompleted("cancelled");
                return;
            }
            orders.recordInventoryAllocated(orderId, allocation.allocations());
        }
        // OrderConfirmed is emitted inside the aggregate once both steps have been recorded.
        metrics.sagaCompleted("confirmed");
    }

    private void compensatePayment(String authorizationId) {
        if (authorizationId != null) {
            try {
                paymentGateway.voidAuthorization(authorizationId);
            } catch (RuntimeException e) {
                log.warn("failed to void authorization {} during compensation", authorizationId, e);
            }
        }
    }
}
