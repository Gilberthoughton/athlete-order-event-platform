# ADR 0008 — Consumers must be idempotent; the platform guarantees at-least-once delivery

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

The transactional outbox ([ADR 0002](0002-transactional-outbox-for-publishing.md)) gives
**at-least-once** delivery: the relay may publish an event, fail before recording success,
and re-publish on restart. Kafka consumer-offset commits are likewise not perfectly atomic
with side effects, so a consumer may see the **same event more than once** after a rebalance
or redelivery. Achieving true end-to-end exactly-once across a database and a broker is, in
the general case, not possible; pretending otherwise is a classic distributed-systems mistake.

The honest and robust design is therefore: **deliver at least once, process effectively once
by making consumers idempotent.**

## Decision

1. The platform commits to an **at-least-once** delivery contract — documented for every consumer.
2. **Every consumer must be idempotent**: processing the same event twice produces the same
   result as processing it once.

Standard idempotency mechanisms for our consumers:

- **Inbox / processed-event table:** record `eventId` in a uniqueness-constrained table inside
  the same transaction as the side effect; a duplicate `eventId` short-circuits. (`eventId` is
  part of the event metadata envelope — see the [event catalog](../architecture/event-catalog.md).)
- **Idempotent upserts:** express the side effect as an `INSERT … ON CONFLICT DO NOTHING/UPDATE`
  keyed by a natural idempotency key, so replays converge to the same state.
- **Natural idempotency:** prefer operations that are inherently repeatable (set status =
  `CONFIRMED`) over non-idempotent ones (increment a counter).

Commands at the write side carry an **idempotency key** as well, so a retried `PlaceOrder`
does not create two orders.

## Consequences

**Positive**

- Correct behavior under redelivery, consumer rebalances, and relay retries.
- Operationally forgiving: the relay and consumers can retry freely without fear of double effects.

**Negative / trade-offs**

- Every consumer carries a dedupe store and the discipline to use it — a per-consumer cost.
- The inbox table needs retention/cleanup of old `eventId`s.

## Alternatives considered

- **Assume exactly-once and ignore duplicates (rejected):** the most common and most dangerous junior
  assumption; leads to double inventory decrements, duplicate notifications, double charges.
- **Kafka transactional/`read_process_write` exactly-once (partial, not sufficient):** provides
  exactly-once *within Kafka-to-Kafka* pipelines, but our consumers write to external databases and
  call other systems, where it does not extend. Idempotency at the side-effect boundary is still required.
