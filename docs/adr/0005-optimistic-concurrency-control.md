# ADR 0005 — Optimistic concurrency via a unique `(aggregate_id, sequence_no)` constraint

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

Two commands can target the same order concurrently — e.g. a `CancelOrder` from the athlete
and an `AllocateInventory` from the fulfillment flow arriving at nearly the same instant.
Each command handler:

1. Loads the order by replaying its events,
2. Decides on new events based on the *current* state,
3. Appends those new events.

If both handlers read the same starting state and both append, one decision was made against
stale state — a **lost update**. The order could be both cancelled and allocated. Event-sourced
systems must defend against this explicitly; there is no row-level "last write wins" to fall back on.

## Decision

Each event carries a monotonically increasing **`sequence_no`** within its aggregate. The
event store enforces a **unique constraint on `(aggregate_id, sequence_no)`**.

A command handler loads the aggregate at version *N* and appends new events at versions
*N+1, N+2, …*. If a concurrent writer already committed version *N+1*, the unique constraint
**rejects the insert** with a violation. The handler surfaces this as a
`ConcurrencyConflictException`, and the caller retries: reload (now at the newer version),
re-decide, re-append. This is optimistic locking — we assume conflicts are rare and detect
rather than prevent them.

## Consequences

**Positive**

- Correctness under concurrency with no pessimistic locks and no lock contention on the happy path.
- The expected-version check is a natural fit for an HTTP `If-Match`/ETag concurrency API at the edge.
- Retries are cheap because aggregates are small ([ADR 0006](0006-defer-aggregate-snapshots.md)).

**Negative / trade-offs**

- Callers (or a retry wrapper) must handle `ConcurrencyConflictException`.
- Pathologically hot aggregates could see repeated retries; orders are not hot enough for this to matter.

## Alternatives considered

- **Pessimistic locking (`SELECT … FOR UPDATE`) (rejected):** serializes all access to an aggregate and
  invites lock contention and deadlocks for a conflict rate that is genuinely low for orders.
- **Last-write-wins / no concurrency control (rejected):** silently corrupts state; unacceptable for an
  order system handling payment and inventory.
- **A version column on a current-state row (rejected):** that is the classic JPA `@Version` approach for
  mutable state — but we have no mutable current-state row by design; the event sequence *is* the version.
