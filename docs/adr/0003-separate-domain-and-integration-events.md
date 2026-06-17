# ADR 0003 — Separate internal domain events from public integration events

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

The word "event" is overloaded. This system has two distinct kinds, and conflating them is
one of the most common design failures in event-driven systems:

1. **Domain events** — fine-grained facts the `Order` aggregate emits to record its own state
   transitions (e.g. `OrderLineAdjusted`, `PaymentAuthorizationDeclined`). They are *implementation
   detail*. They are shaped for rebuilding the aggregate and will change as the domain model evolves.
2. **Integration events** — coarse-grained facts published to *other bounded contexts*
   (inventory, fulfillment, notifications) so they can react (e.g. `OrderConfirmed`,
   `OrderCancelled`). These form a **public contract** consumed by teams we do not control.

If internal domain events are published directly to Kafka, every downstream consumer becomes
coupled to our internal model. We can no longer refactor the aggregate without breaking them,
and the "public API" of the order service becomes an accidental leak of private structure.

## Decision

Maintain **two separate event vocabularies**:

- Domain events live only in the Postgres event store and are never published externally.
- Integration events are an explicitly designed, versioned contract published to Kafka
  (via the outbox, [ADR 0002](0002-transactional-outbox-for-publishing.md)).

An **anti-corruption / translation step** maps "one or more domain events" → "an integration
event." Not every domain event produces an integration event, and an integration event may
summarize several domain events.

## Consequences

**Positive**

- The aggregate's internal model can evolve freely without breaking consumers.
- The public contract is small, intentional, and documented in the [event catalog](../architecture/event-catalog.md).
- Integration events can be enriched (denormalized) for consumer convenience without polluting the domain model.

**Negative / trade-offs**

- A translation layer must be written and tested (contract tests guard the public shape).
- Some duplication between domain and integration event definitions.
- Developers must understand *which* vocabulary they are working in — a discipline cost.

## Alternatives considered

- **Publish domain events directly (rejected):** simplest to build, but couples all consumers to
  internal structure and turns every refactor into a breaking change. This is the junior default.
- **Single shared event library across all services (rejected):** creates a distributed monolith —
  a shared compile-time dependency that forces lockstep deployments. Contracts should be shared as
  *schemas* ([ADR 0004](0004-avro-schema-registry-backward-compat.md)), not as shared code.
