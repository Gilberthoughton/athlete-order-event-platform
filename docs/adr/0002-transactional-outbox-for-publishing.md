# ADR 0002 — Publish to Kafka via a transactional outbox, not dual writes

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

When an order command is handled, two things must happen: new events are appended to the
Postgres event store, and the corresponding integration events must reach Kafka so other
services react. These are writes to **two different systems** with no shared transaction.

The naïve implementation — append to Postgres, then call `kafkaTemplate.send(...)` in the
same method — is a **dual write**. It is not atomic:

- If the process crashes after the DB commit but before the Kafka send, the event is lost
  to downstream consumers forever — inventory is never decremented, the athlete is never
  notified. The system is silently inconsistent.
- If the send succeeds but the DB transaction rolls back, consumers act on an event that
  "never happened."

Wrapping a Kafka send inside a Spring `@Transactional` boundary does **not** make this
atomic across the two systems; it is a common and dangerous misconception.

## Decision

Use the **transactional outbox pattern**. Within the *same* Postgres transaction that
appends domain events, insert a row into an `outbox` table describing the integration
event(s) to publish. A separate **message relay** reads the outbox and publishes to Kafka,
marking rows as dispatched.

Because the event append and the outbox insert commit together, we never lose an event and
never publish a phantom. Delivery to Kafka becomes **at-least-once** (the relay may retry
and re-send), which is why consumers must be idempotent (see [ADR 0008](0008-idempotent-consumers.md)).

## Decision detail — relay mechanism

This repository ships a **polling outbox publisher** as the relay, and documents **Debezium
change-data-capture** as the production upgrade path. The relay is encapsulated behind an
interface (`MessageRelay`) so swapping implementations does not touch domain code.

- **Polling publisher (shipped):** a scheduled query
  (`SELECT ... WHERE dispatched_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED`) that
  publishes undispatched rows to Kafka and stamps `dispatched_at`. `SKIP LOCKED` lets multiple
  instances poll concurrently without contention. Higher latency and some DB load, but **no
  Kafka Connect/Debezium dependency** — the whole stack boots from one `compose.yaml` on a
  reviewer's laptop with `docker compose up`.
- **Debezium (CDC) — documented upgrade path:** tails the Postgres WAL and emits a Kafka
  record per outbox insert. No application polling load and lower latency — the production-grade
  choice — at the cost of running Kafka Connect + Debezium and configuring logical replication.

**Why polling first:** for a runnable reference implementation, a clean one-command boot is
worth more than shaving relay latency. The polling implementation still demonstrates every
property that matters — atomic outbox write, at-least-once delivery, ordered per-key publish,
idempotent consumers — and the `MessageRelay` seam makes the CDC swap a localized,
infrastructure-only change. See [ADR 0010](0010-module-topology.md) for how the relay is
deployed within the service.

## Consequences

**Positive**

- Eliminates dual-write data loss — the central correctness property of the platform.
- The outbox doubles as an audit trail of what was published and when.
- Clean separation: domain code only writes to Postgres; nothing in the aggregate knows about Kafka.

**Negative / trade-offs**

- At-least-once delivery pushes a deduplication requirement onto every consumer.
- Adds operational components (Debezium/Kafka Connect, or a polling scheduler with backpressure).
- The outbox table needs its own retention/cleanup of dispatched rows.

## Alternatives considered

- **Dual write (rejected):** non-atomic, loses data on partial failure. The anti-pattern this ADR exists to prevent.
- **Kafka transactions / `exactly-once` across DB + Kafka (rejected):** Kafka's transactional
  producer gives exactly-once *within Kafka*, not across an external database. It does not solve the dual-write problem here.
- **Listen-to-yourself / event-store-as-source for the relay (considered):** publish by tailing the
  `events` table directly instead of a separate `outbox`. Viable, but a dedicated outbox lets us
  shape the *public* integration contract independently of internal events ([ADR 0003](0003-separate-domain-and-integration-events.md)).
