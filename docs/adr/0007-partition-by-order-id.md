# ADR 0007 — Partition Kafka topics by `orderId` for per-aggregate ordering

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

Kafka guarantees ordering **only within a partition**, not across a topic. Integration events
for an order have causal dependencies a consumer must respect: `OrderConfirmed` must be
processed before `OrderShipped`, which must precede `OrderDelivered`. If these land on
different partitions, a consumer with multiple workers can process them out of order.

The instinct to want "global ordering across all orders" is a misunderstanding of the
requirement and does not scale — a single ordered partition caps total throughput at one
consumer thread. We need to identify the **actual** ordering guarantee required.

## Decision

Use **`orderId` as the Kafka partition key** for all order integration-event topics.

Kafka routes all records with the same key to the same partition, so **all events for a
given order are strictly ordered**, while events for *different* orders are spread across
partitions and processed in parallel. This delivers exactly the guarantee the domain needs —
**per-aggregate ordering** — and nothing more, which is what makes it scalable.

## Consequences

**Positive**

- Correct causal ordering per order without sacrificing horizontal throughput.
- Consumer parallelism scales with partition count; orders are independent units of work.
- Naturally aligns with the aggregate boundary — the order is the unit of consistency end to end.

**Negative / trade-offs**

- **No total order across orders.** Any logic needing a global sequence must derive it elsewhere
  (e.g. event-store `sequence_no` or an explicit timeline projection). This is acceptable —
  cross-order global ordering is not a real business requirement here.
- **Partition-count changes rehash keys.** Repartitioning a live topic breaks the
  key→partition mapping, so partition count must be sized up front with headroom for peak
  (e.g. holiday/back-to-school) load.
- A pathologically hot single order could create partition skew; real order traffic does not.

## Alternatives considered

- **Single partition for global ordering (rejected):** correct ordering but throughput-capped at one
  consumer; defeats the platform's high-throughput goal.
- **Round-robin / no key (rejected):** maximizes parallelism but destroys per-order ordering — a consumer
  could ship before confirming. Unacceptable.
- **Key by athlete/customer id (rejected):** would serialize all of one athlete's orders unnecessarily and
  create skew for high-volume accounts; the order is the correct consistency boundary, not the athlete.
