# ADR 0006 — Defer aggregate snapshots; orders are naturally bounded

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

A standard performance optimization in event sourcing is **snapshotting**: periodically
persisting a serialized copy of an aggregate's state so rehydration replays only the events
since the last snapshot instead of the full stream. It is genuinely necessary for long-lived
aggregates with thousands of events (e.g. a bank account open for decades).

There is pressure to add snapshots reflexively because they are a "known event-sourcing
feature." This ADR exists to record a deliberate decision **not** to — and why that is the
senior choice here.

## Decision

**Do not implement snapshots in the initial design.** Rehydrate `Order` aggregates by
replaying their full event stream from `sequence_no = 0`.

## Rationale

An `Order` is a **naturally bounded aggregate**. Across its entire lifecycle — placed,
authorized, allocated, confirmed, shipped (possibly split), delivered, and occasionally
cancelled or returned — it accumulates on the order of **tens of events**, not thousands.
Replaying tens of small events with an indexed `WHERE aggregate_id = ?` query is sub-millisecond.

Adding snapshots now would buy no measurable performance while adding real cost:

- A snapshot store, snapshot versioning, and snapshot-schema migration on every aggregate change.
- A subtle correctness surface (a stale or mis-versioned snapshot silently corrupts rehydration).
- Cognitive load that obscures the core event-sourcing model for readers of the code.

**Choosing not to build a feature, and justifying it, is itself an architectural decision.**
Premature snapshotting is gold-plating.

## Consequences

**Positive**

- Simpler code and storage; one obvious path to current state.
- No snapshot-versioning migration burden as the domain model evolves.

**Negative / trade-offs**

- If a future aggregate (e.g. a long-running fulfillment saga, or a per-athlete lifetime
  ledger) accumulates large streams, this decision must be revisited for *that* aggregate.

## Revisit trigger

Reopen this ADR if profiling shows aggregate rehydration p99 exceeding ~5 ms, or if any
aggregate's typical stream length exceeds a few hundred events. The replay-based load is
already abstracted behind the repository, so introducing snapshots later is a localized change.

## Alternatives considered

- **Snapshot every N events (rejected now):** the conventional approach; unjustified for bounded order streams.
- **Snapshot-on-write of a denormalized current-state row (rejected):** blurs the line between event
  store and read model; the read-model projections already serve fast current-state queries (CQRS).
