# ADR 0001 — PostgreSQL is the authoritative event store; Kafka is the integration backbone

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

This is an event-sourced system: the state of an `Order` is derived by folding an
ordered, immutable stream of events rather than read from a mutable row. Two storage
technologies are candidates for holding that stream of truth:

1. **Apache Kafka** as the event store (a topic, or compacted topic, per aggregate type).
2. **PostgreSQL** as the event store (an append-only `events` table), with Kafka used
   only to move events between services.

The decision is foundational — nearly every other ADR depends on it — and is expensive
to reverse once aggregates and projections are built against it.

Key forces:

- We must **load a single order's full history by key** cheaply and frequently (to
  rehydrate the aggregate before handling a command).
- We need **optimistic concurrency** to safely handle concurrent commands on the same order.
- We need **strong transactional guarantees** when appending events.
- We need to publish events to other bounded contexts (inventory, fulfillment, notifications).

## Decision

**PostgreSQL is the authoritative, system-of-record event store.** Events are appended to
an append-only table and are never updated or deleted. **Kafka is the integration
backbone** — a transport for publishing *integration events* (see [ADR 0003](0003-separate-domain-and-integration-events.md))
to downstream consumers — not the store of record.

## Consequences

**Positive**

- Loading an aggregate is a single indexed query: `SELECT ... WHERE aggregate_id = ? ORDER BY sequence_no`.
- Optimistic concurrency is enforced with a unique constraint (see [ADR 0005](0005-optimistic-concurrency-control.md)).
- Appending events and writing the outbox row happen in **one ACID transaction**
  (see [ADR 0002](0002-transactional-outbox-for-publishing.md)), eliminating dual-write data loss.
- Projections can be rebuilt deterministically by replaying the table from `sequence_no = 0`.
- Operationally familiar: backups, point-in-time recovery, and partitioning are well understood.

**Negative / trade-offs**

- The event table grows unbounded; we plan **range partitioning by time** and an archival
  strategy for cold partitions.
- Postgres is not a streaming system; fan-out to consumers is Kafka's job, reached via the outbox.
- A relay process (CDC or polling) is required to move committed events into Kafka — added moving part.

## Alternatives considered

**Kafka as the event store (rejected).** Attractive because the events are "already in
Kafka," but: loading one aggregate's history means scanning a partition or maintaining an
external index; optimistic concurrency requires an external coordination mechanism; and
log retention/compaction semantics fight against "keep every event forever, queryable by
key." Kafka is an excellent *log for transport*, a poor *database for aggregates*.

**A dedicated event-store product (EventStoreDB) (rejected for now).** Purpose-built and
elegant, but adds an unfamiliar operational dependency and a smaller ecosystem. For a
portfolio that targets a PostgreSQL + Kafka shop, demonstrating the pattern on
commodity infrastructure is more valuable than adopting a niche store. Revisit if write
throughput on a single Postgres primary becomes the bottleneck.
