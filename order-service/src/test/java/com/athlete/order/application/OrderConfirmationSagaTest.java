package com.athlete.order.application;

import com.athlete.order.application.contract.IntegrationEvent;
import com.athlete.order.application.port.PaymentGateway;
import com.athlete.order.domain.Order;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.FulfillmentType;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.OrderStatus;
import com.athlete.order.domain.model.Quantity;
import com.athlete.order.domain.model.Sku;
import com.athlete.order.infrastructure.stub.StubInventoryAllocator;
import com.athlete.order.infrastructure.stub.StubPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the confirmation saga over in-memory fakes (no Spring, no DB) to prove the orchestration:
 * the happy path, both failure/compensation branches, transient-failure retry, and idempotent
 * re-drive.
 */
class OrderConfirmationSagaTest {

    private InMemoryEventStore eventStore;
    private RecordingOutbox outbox;
    private OrderApplicationService orders;

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
        outbox = new RecordingOutbox();
        orders = new OrderApplicationService(eventStore, outbox, new IntegrationEventMapper(), event -> {
        });
    }

    @Test
    void happy_path_confirms_and_publishes_order_confirmed() {
        OrderId id = place(line("SHOE-1", 1, "129.99"));
        saga(new StubPaymentGateway(), new StubInventoryAllocator()).drive(id);

        assertThat(status(id)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outbox.published()).hasSize(1)
                .first().isInstanceOf(IntegrationEvent.OrderConfirmed.class);
    }

    @Test
    void declined_payment_cancels_and_publishes_order_cancelled() {
        OrderId id = place(line("SHOE-1", 1, "5000.00")); // at/above the stub's decline threshold
        saga(new StubPaymentGateway(), new StubInventoryAllocator()).drive(id);

        assertThat(status(id)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outbox.published()).hasSize(1)
                .first().isInstanceOf(IntegrationEvent.OrderCancelled.class);
    }

    @Test
    void out_of_stock_compensates_payment_and_cancels() {
        RecordingPaymentGateway payment = new RecordingPaymentGateway();
        OrderId id = place(line("OOS-SHOE-9", 1, "80.00"));

        saga(payment, new StubInventoryAllocator()).drive(id);

        assertThat(status(id)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment.voided()).isTrue(); // compensation ran
        assertThat(outbox.published()).hasSize(1)
                .first().isInstanceOf(IntegrationEvent.OrderCancelled.class);
    }

    @Test
    void transient_payment_failures_are_retried_then_confirm() {
        OrderId id = place(line("SHOE-1", 1, "60.00"));
        saga(new FlakyPaymentGateway(2), new StubInventoryAllocator()).drive(id);

        assertThat(status(id)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void re_driving_a_confirmed_order_is_idempotent() {
        OrderId id = place(line("SHOE-1", 1, "60.00"));
        OrderConfirmationSaga saga = saga(new StubPaymentGateway(), new StubInventoryAllocator());

        saga.drive(id);
        int eventsAfterFirst = eventStore.eventCount(id);
        saga.drive(id); // should be a no-op

        assertThat(status(id)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(eventStore.eventCount(id)).isEqualTo(eventsAfterFirst);
        assertThat(outbox.published()).hasSize(1);
    }

    // ---- helpers ----

    private OrderConfirmationSaga saga(PaymentGateway payment, StubInventoryAllocator inventory) {
        return new OrderConfirmationSaga(orders, payment, inventory);
    }

    private OrderId place(OrderLine... lines) {
        return orders.placeOrder(new PlaceOrderCommand(new AthleteId(UUID.randomUUID()), List.of(lines)));
    }

    private OrderStatus status(OrderId id) {
        return orders.find(id).map(Order::status).orElseThrow();
    }

    private static OrderLine line(String sku, int qty, String unitPrice) {
        return new OrderLine(new Sku(sku), Quantity.of(qty), Money.of(unitPrice, "USD"), FulfillmentType.SHIP);
    }

    /** Authorizes on the first attempt but records whether a compensating void was issued. */
    private static final class RecordingPaymentGateway implements PaymentGateway {
        private boolean voided;

        @Override
        public Result authorize(OrderId orderId, Money amount) {
            return Result.authorized("AUTH-REC");
        }

        @Override
        public void voidAuthorization(String authorizationId) {
            this.voided = true;
        }

        boolean voided() {
            return voided;
        }
    }

    /** Throws a transient failure a fixed number of times, then authorizes. */
    private static final class FlakyPaymentGateway implements PaymentGateway {
        private int failuresRemaining;

        FlakyPaymentGateway(int failures) {
            this.failuresRemaining = failures;
        }

        @Override
        public Result authorize(OrderId orderId, Money amount) {
            if (failuresRemaining-- > 0) {
                throw new TransientFailureException("temporary gateway error");
            }
            return Result.authorized("AUTH-FLAKY");
        }

        @Override
        public void voidAuthorization(String authorizationId) {
        }
    }
}
