# ADR 0010 — Module topology: one order service + one downstream consumer

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

## Context

The platform is event-driven, and a reviewer must be able to *see* that — events leaving one
deployable and being acted on by another, across a real broker. At the same time, the primary
project must stay small enough to read and maintain. We need a topology that makes the
distributed, event-driven nature obvious without fragmenting the system into more services
than the reference implementation warrants.

## Decision

Ship a **two-module Gradle build**:

1. **`order-service`** — one Spring Boot application that owns the order lifecycle. Internally
   it is organized into clear, dependency-directed boundaries:
   - `domain` — pure aggregate, value objects, domain events (no framework, no I/O).
   - `application` — command handlers, the confirmation saga, and **ports** (`EventStore`,
     `OutboxRepository`, `PaymentGateway`, `InventoryAllocator`).
   - `infrastructure` — adapters: JDBC event store + outbox, the polling relay, projections,
     Kafka producer, stub gateways.
   - `api` — REST controllers.

   The relay and projection workers run **inside** this app (as scheduled components) for the
   reference build, but sit behind interfaces so they can be extracted into their own
   deployables without touching domain or application code.

2. **`inventory-consumer`** — a separate, deliberately small Spring Boot application that
   subscribes to the public order integration events on Kafka, dedupes them
   ([ADR 0008](0008-idempotent-consumers.md)), and maintains its own read model. It shares
   nothing with `order-service` at compile time — it consumes the **wire contract** only
   ([ADR 0003](0003-separate-domain-and-integration-events.md)), defining its own local view of
   the events. This is what makes the event-driven boundary real rather than an in-process call.

The dependency rule across `order-service`'s internal packages points inward:
`api → application → domain`, and `infrastructure → application` (implementing ports). The
domain depends on nothing.

## Consequences

**Positive**

- The event-driven architecture is undeniable: a second process reacts to events over Kafka.
- `inventory-consumer` sharing no code proves the integration contract is a true public boundary.
- Internal boundaries in `order-service` keep it a modular monolith that could be split later.
- Small surface area: two focused apps, one `compose.yaml`, one command to run each.

**Negative / trade-offs**

- Two apps and two databases to run locally (mitigated by `compose.yaml` + documented commands).
- Some duplication: the consumer re-declares the shape of the events it cares about (intentional —
  it is decoupling, not accidental duplication).

## Alternatives considered

- **Single application, in-process event handling (rejected):** simplest, but the "event-driven,
  distributed" claim would not be visible to a reviewer — the defining quality of the project.
- **Full microservice split (order, payment, inventory, fulfillment, notifications) (rejected for
  this repo):** demonstrates more topology but explodes maintenance and obscures the core
  event-sourcing story. Inventory/fulfillment depth is the subject of the separate Supply Chain
  Fulfillment Service project; here we keep one representative consumer.
- **Shared event-contract library across modules (rejected):** a compile-time shared types module
  would couple the consumer to the producer's code and recreate a distributed monolith; contracts
  are shared as wire schemas, not Java types.
