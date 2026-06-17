package com.athlete.order.domain;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.event.DomainEvent.InventoryAllocated;
import com.athlete.order.domain.event.DomainEvent.OrderConfirmed;
import com.athlete.order.domain.event.DomainEvent.OrderPlaced;
import com.athlete.order.domain.event.DomainEvent.PaymentAuthorized;
import com.athlete.order.domain.model.Allocation;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.FulfillmentType;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.OrderStatus;
import com.athlete.order.domain.model.Quantity;
import com.athlete.order.domain.model.Sku;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Given-When-Then tests over the pure Order aggregate: given prior events (or a fresh aggregate),
 * when a command is applied, then assert the emitted events and resulting state. No framework, no
 * I/O — these are the primary correctness net for domain invariants.
 */
class OrderTest {

    @Test
    void placing_an_order_emits_order_placed_and_awaits_confirmation() {
        Order order = Order.place(OrderId.newId(), anAthlete(), List.of(line("SHOE-1", 2, "50.00")));

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_CONFIRMATION);
        assertThat(order.total()).isEqualTo(Money.of("100.00", "USD"));
        assertThat(order.pendingEvents()).singleElement().isInstanceOf(OrderPlaced.class);
        assertThat(order.version()).isEqualTo(-1L);
    }

    @Test
    void payment_then_allocation_confirms_the_order() {
        Order order = placedAndCommitted();

        order.authorizePayment("AUTH-1", order.total());
        order.allocateInventory(List.of(new Allocation(new Sku("SHOE-1"), 1, "DC-1")));

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(simpleNames(order.pendingEvents()))
                .containsExactly("PaymentAuthorized", "InventoryAllocated", "OrderConfirmed");
    }

    @Test
    void allocation_then_payment_confirms_regardless_of_step_order() {
        Order order = placedAndCommitted();

        order.allocateInventory(List.of(new Allocation(new Sku("SHOE-1"), 1, "DC-1")));
        order.authorizePayment("AUTH-1", order.total());

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(simpleNames(order.pendingEvents()))
                .containsExactly("InventoryAllocated", "PaymentAuthorized", "OrderConfirmed");
    }

    @Test
    void declined_payment_cancels_the_order() {
        Order order = placedAndCommitted();

        order.declinePayment("LIMIT_EXCEEDED");

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(simpleNames(order.pendingEvents()))
                .containsExactly("PaymentAuthorizationDeclined", "OrderCancelled");
    }

    @Test
    void failed_allocation_cancels_the_order() {
        Order order = placedAndCommitted();
        order.authorizePayment("AUTH-1", order.total());
        order.markEventsCommitted();

        order.failInventoryAllocation("OUT_OF_STOCK");

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(simpleNames(order.pendingEvents()))
                .containsExactly("InventoryAllocationFailed", "OrderCancelled");
    }

    @Test
    void authorizing_payment_twice_is_idempotent() {
        Order order = placedAndCommitted();
        order.authorizePayment("AUTH-1", order.total());
        order.markEventsCommitted();

        order.authorizePayment("AUTH-2", order.total());

        assertThat(order.pendingEvents()).isEmpty();
        assertThat(order.paymentAuthorized()).isTrue();
    }

    @Test
    void commands_on_a_cancelled_order_are_rejected() {
        Order order = placedAndCommitted();
        order.declinePayment("LIMIT_EXCEEDED");

        assertThatThrownBy(() -> order.authorizePayment("AUTH-1", order.total()))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void rehydration_reconstructs_state_and_version_from_history() {
        OrderId id = OrderId.newId();
        AthleteId athlete = anAthlete();
        List<OrderLine> lines = List.of(line("SHOE-1", 1, "50.00"));
        Money total = Money.of("50.00", "USD");
        List<DomainEvent> history = List.of(
                new OrderPlaced(id, athlete, lines, total, Instant.now()),
                new PaymentAuthorized(id, "AUTH-1", total, Instant.now()),
                new InventoryAllocated(id, List.of(new Allocation(new Sku("SHOE-1"), 1, "DC-1")), Instant.now()),
                new OrderConfirmed(id, Instant.now()));

        Order order = Order.rehydrate(history);

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.version()).isEqualTo(3L);
        assertThat(order.pendingEvents()).isEmpty();
    }

    // ---- helpers ----

    private static Order placedAndCommitted() {
        Order order = Order.place(OrderId.newId(), anAthlete(), List.of(line("SHOE-1", 1, "50.00")));
        order.markEventsCommitted(); // simulate the placed event being persisted
        return order;
    }

    private static AthleteId anAthlete() {
        return new AthleteId(UUID.randomUUID());
    }

    private static OrderLine line(String sku, int qty, String unitPrice) {
        return new OrderLine(new Sku(sku), Quantity.of(qty), Money.of(unitPrice, "USD"), FulfillmentType.SHIP);
    }

    private static List<String> simpleNames(List<DomainEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }
}
