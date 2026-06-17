# ADR 0009 — Model order confirmation as a lightweight saga / process manager

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

Reaching the `Confirmed` state requires **two independent side-effecting steps** that can each
succeed or fail: authorizing payment (external gateway) and allocating inventory (inventory
context). Neither lives inside the `Order` aggregate's transaction, and either can fail
independently. We need a place to coordinate them, react to their outcomes, drive the
follow-up commands, and **compensate** when one step fails after the other has already
succeeded (e.g. inventory allocation fails after payment was authorized → the authorization
must be voided and the order cancelled).

Putting this orchestration inside the aggregate would force it to perform I/O and hold
cross-service state it does not own. Scattering it across controllers or the consumer would
hide the workflow and make failure handling ad hoc.

## Decision

Introduce an explicit **`OrderConfirmationSaga`** (a process manager). It is triggered by the
`OrderPlaced` domain event and orchestrates the path to confirmation:

1. Request **payment authorization** (via a `PaymentGateway` port).
   - Authorized → record `PaymentAuthorized` on the aggregate.
   - Declined → record `PaymentAuthorizationDeclined` → the order is cancelled.
2. Request **inventory allocation** (via an `InventoryAllocator` port).
   - Allocated → record `InventoryAllocated`.
   - Failed → record `InventoryAllocationFailed`, **compensate** (void the prior payment
     authorization), and cancel the order.
3. When payment is authorized **and** inventory is allocated → issue `ConfirmOrder` →
   `OrderConfirmed` (which becomes the public `OrderConfirmed` integration event).

The saga's external dependencies are **ports with stub adapters** in this repository
(`StubPaymentGateway`, `StubInventoryAllocator`) — deterministic by order content so both the
happy path and every failure branch are demonstrable and testable. Replacing the stubs with
real gateways is an adapter swap; the saga and aggregate are untouched.

### Properties the saga must demonstrate

- **State transitions** driven by step outcomes (the lifecycle in [order-lifecycle.md](../architecture/order-lifecycle.md)).
- **Failure handling & compensation** — payment-declined and allocation-failed branches both
  lead to a clean `Cancelled` terminal state; allocation failure voids the authorization.
- **Retry** — transient port failures are retried with bounded attempts before the step is
  treated as failed.
- **Idempotency** — every command the saga issues is idempotent at the aggregate
  (re-recording an already-recorded authorization is a no-op), and optimistic concurrency
  ([ADR 0005](0005-optimistic-concurrency-control.md)) guards concurrent progression, so a
  redelivered or retried trigger cannot double-confirm.

## Trigger mechanism (this phase)

The saga reacts to `OrderPlaced` via an **after-commit application hook** (Spring
`@TransactionalEventListener(AFTER_COMMIT)`), so it runs only once the order is durably
persisted. This in-process trigger is sufficient for the reference implementation; a crash
between commit and saga execution is recovered by a periodic **reconciliation sweep** that
re-drives orders left in a non-terminal pre-confirmation state. A fully durable,
broker-backed trigger is a later-phase hardening, isolated to the trigger seam.

## Consequences

**Positive**

- The end-to-end workflow lives in one named, testable component with explicit branches.
- Compensation and retry are first-class, not afterthoughts — a strong distributed-systems signal.
- The aggregate stays pure; all cross-service coordination is outside it.

**Negative / trade-offs**

- A process manager is additional moving machinery and its own state to reason about.
- The in-process after-commit trigger needs the reconciliation sweep to be crash-safe.

## Alternatives considered

- **Orchestrate inside the aggregate (rejected):** forces I/O and foreign state into a pure
  domain object; untestable and a layering violation.
- **Two-phase commit across payment/inventory/DB (rejected):** distributed 2PC is operationally
  brittle and unavailable across an external gateway; sagas with compensation are the standard answer.
- **Pure choreography (no orchestrator) (considered):** each context reacts to events with no
  central coordinator. Viable and more decoupled, but the confirmation flow's compensation logic
  is clearer and more demonstrable as an explicit orchestrator for a single-repo reference. The
  downstream integration ([ADR 0003](0003-separate-domain-and-integration-events.md)) remains
  choreography-style; only the *confirmation* step is orchestrated.
