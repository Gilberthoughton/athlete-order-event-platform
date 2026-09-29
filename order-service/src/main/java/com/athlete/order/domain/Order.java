package com.athlete.order.domain;

import com.athlete.order.domain.event.DomainEvent;
import com.athlete.order.domain.event.DomainEvent.InventoryAllocated;
import com.athlete.order.domain.event.DomainEvent.InventoryAllocationFailed;
import com.athlete.order.domain.event.DomainEvent.OrderCancelled;
import com.athlete.order.domain.event.DomainEvent.OrderConfirmed;
import com.athlete.order.domain.event.DomainEvent.OrderPlaced;
import com.athlete.order.domain.event.DomainEvent.PaymentAuthorizationDeclined;
import com.athlete.order.domain.event.DomainEvent.PaymentAuthorized;
import com.athlete.order.domain.model.Allocation;
import com.athlete.order.domain.model.AthleteId;
import com.athlete.order.domain.model.Money;
import com.athlete.order.domain.model.OrderId;
import com.athlete.order.domain.model.OrderLine;
import com.athlete.order.domain.model.OrderStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Order aggregate — the consistency boundary and source of business rules.
 *
 * <p>State is never set directly; every transition is expressed as a {@link DomainEvent} that is
 * both applied to in-memory state and recorded as a pending event to be appended to the event
 * store. The aggregate performs no I/O, which keeps its rules exhaustively testable with
 * given-when-then unit tests.
 *
 * <p>{@link #version()} is the sequence number of the last <em>committed</em> event (-1 for a new
 * aggregate) and serves as the optimistic-concurrency token when appending (see ADR 0005).
 */
public final class Order {

    private OrderId id;
    private AthleteId athleteId;
    private List<OrderLine> lines = List.of();
    private Money total;
    private OrderStatus status;
    private boolean paymentAuthorized;
    private boolean inventoryAllocated;
    private String paymentAuthorizationId;

    private long version = -1;
    private final List<DomainEvent> pending = new ArrayList<>();

    private Order() {
    }

    /** Reconstructs an aggregate by folding its committed event history. */
    public static Order rehydrate(List<DomainEvent> history) {
        Order order = new Order();
        for (DomainEvent event : history) {
            order.mutate(event);
            order.version++;
        }
        return order;
    }

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    public static Order place(OrderId id, AthleteId athleteId, List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("an order must have at least one line");
        }
        Money orderTotal = lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(Money::add)
                .orElseThrow();

        Order order = new Order();
        order.raise(new OrderPlaced(id, athleteId, List.copyOf(lines), orderTotal, Instant.now()));
        return order;
    }

    public void authorizePayment(String authorizationId, Money amount) {
        ensureNotCancelled();
        if (paymentAuthorized) {
            return; // idempotent: re-recording an authorization is a no-op
        }
        raise(new PaymentAuthorized(id, authorizationId, amount, Instant.now()));
        confirmIfReady();
    }

    public void declinePayment(String reasonCode) {
        if (isTerminal()) {
            return;
        }
        raise(new PaymentAuthorizationDeclined(id, reasonCode, Instant.now()));
        raise(new OrderCancelled(id, "PAYMENT_DECLINED:" + reasonCode, Instant.now()));
    }

    public void allocateInventory(List<Allocation> allocations) {
        ensureNotCancelled();
        if (inventoryAllocated) {
            return; // idempotent
        }
        raise(new InventoryAllocated(id, List.copyOf(allocations), Instant.now()));
        confirmIfReady();
    }

    public void failInventoryAllocation(String reasonCode) {
        if (isTerminal()) {
            return;
        }
        raise(new InventoryAllocationFailed(id, reasonCode, Instant.now()));
        raise(new OrderCancelled(id, "ALLOCATION_FAILED:" + reasonCode, Instant.now()));
    }

    public void cancel(String reasonCode) {
        if (status == OrderStatus.CANCELLED) {
            return; // idempotent
        }
        if (status == OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "a confirmed order cannot be cancelled; returns are handled by a separate flow that is not built");
        }
        raise(new OrderCancelled(id, reasonCode, Instant.now()));
    }

    private void confirmIfReady() {
        if (status == OrderStatus.AWAITING_CONFIRMATION && paymentAuthorized && inventoryAllocated) {
            raise(new OrderConfirmed(id, Instant.now()));
        }
    }

    private void ensureNotCancelled() {
        if (status == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException("order " + id + " is cancelled");
        }
    }

    private boolean isTerminal() {
        return status == OrderStatus.CANCELLED || status == OrderStatus.CONFIRMED;
    }

    // ---------------------------------------------------------------------
    // Event-sourcing plumbing
    // ---------------------------------------------------------------------

    private void raise(DomainEvent event) {
        mutate(event);
        pending.add(event);
    }

    private void mutate(DomainEvent event) {
        switch (event) {
            case OrderPlaced e -> {
                this.id = e.orderId();
                this.athleteId = e.athleteId();
                this.lines = e.lines();
                this.total = e.total();
                this.status = OrderStatus.AWAITING_CONFIRMATION;
            }
            case PaymentAuthorized e -> {
                this.paymentAuthorized = true;
                this.paymentAuthorizationId = e.authorizationId();
            }
            case PaymentAuthorizationDeclined e -> {
                // The cancellation that follows carries the state change.
            }
            case InventoryAllocated e -> this.inventoryAllocated = true;
            case InventoryAllocationFailed e -> {
                // The cancellation that follows carries the state change.
            }
            case OrderConfirmed e -> this.status = OrderStatus.CONFIRMED;
            case OrderCancelled e -> this.status = OrderStatus.CANCELLED;
        }
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public OrderId id() {
        return id;
    }

    public AthleteId athleteId() {
        return athleteId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Money total() {
        return total;
    }

    public OrderStatus status() {
        return status;
    }

    public boolean paymentAuthorized() {
        return paymentAuthorized;
    }

    public boolean inventoryAllocated() {
        return inventoryAllocated;
    }

    public Optional<String> paymentAuthorizationId() {
        return Optional.ofNullable(paymentAuthorizationId);
    }

    public long version() {
        return version;
    }

    public List<DomainEvent> pendingEvents() {
        return List.copyOf(pending);
    }

    public void markEventsCommitted() {
        version += pending.size();
        pending.clear();
    }
}
