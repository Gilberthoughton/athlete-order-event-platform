# Architecture Decision Records

This directory captures the significant, hard-to-reverse decisions behind the Athlete
Order Event Platform. Each record states the **context**, the **decision**, its
**consequences**, and — importantly — the **alternatives that were rejected and why**.

ADRs are immutable once accepted. A decision is changed by writing a new ADR that
supersedes the old one, not by editing history. This mirrors the philosophy of the
system itself: the record of *why* is as valuable as the current state.

## Index

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-postgres-as-authoritative-event-store.md) | PostgreSQL is the authoritative event store; Kafka is the integration backbone | Accepted |
| [0002](0002-transactional-outbox-for-publishing.md) | Publish to Kafka via a transactional outbox, not dual writes | Accepted |
| [0003](0003-separate-domain-and-integration-events.md) | Separate internal domain events from public integration events | Accepted |
| [0004](0004-avro-schema-registry-backward-compat.md) | Avro + Schema Registry with BACKWARD compatibility for integration events | Accepted |
| [0005](0005-optimistic-concurrency-control.md) | Optimistic concurrency via a unique `(aggregate_id, sequence_no)` constraint | Accepted |
| [0006](0006-defer-aggregate-snapshots.md) | Defer aggregate snapshots — orders are naturally bounded | Accepted |
| [0007](0007-partition-by-order-id.md) | Partition Kafka topics by `orderId` for per-aggregate ordering | Accepted |
| [0008](0008-idempotent-consumers.md) | Consumers must be idempotent; the platform guarantees at-least-once delivery | Accepted |
| [0009](0009-saga-for-order-confirmation.md) | Order confirmation modeled as a lightweight saga / process manager | Accepted |
| [0010](0010-module-topology.md) | Two modules: one order service + one downstream consumer | Accepted |

## Format

Records follow a lightweight [MADR](https://adr.github.io/madr/)-style template:
*Context → Decision → Consequences → Alternatives considered*.
