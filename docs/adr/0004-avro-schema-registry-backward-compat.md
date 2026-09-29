# ADR 0004 — Avro + Schema Registry with BACKWARD compatibility for integration events

- **Status:** Accepted — **implemented** (Phase 4)
- **Date:** 2026-06-15 (implemented 2026-06-17)
- **Deciders:** Platform engineering

> **Implemented.** Integration events are now Avro, serialized through the Confluent Schema
> Registry. The contract is [`order-integration-event.avsc`](../../order-service/src/main/avro/order-integration-event.avsc);
> the relay serializes with `KafkaAvroSerializer` and the `inventory-consumer` deserializes
> with `KafkaAvroDeserializer` (specific reader). A `cp-schema-registry` runs in `compose.yaml`
> with compatibility level `BACKWARD`. Tests run the real Confluent serializers against an
> in-JVM mock registry, plus a [SchemaCompatibilityTest](../../order-service/src/test/java/com/athlete/order/contracts/SchemaCompatibilityTest.java)
> that fails the build on a breaking change. See the README "Event Contract Governance" section.
>
> *Earlier phases used a self-describing JSON envelope so the stack booted without a registry;
> the compatibility discipline was followed from day one, so this was a serialization-layer
> swap behind the relay / consumer-deserialization seams, not a redesign.*

## Context

Integration events ([ADR 0003](0003-separate-domain-and-integration-events.md)) are a public
contract consumed by services that deploy on their own schedule. Events are also **immutable
and effectively permanent** — a topic may hold events produced by code that no longer exists.
We therefore need an explicit answer to: *how do event schemas change over time without
breaking producers or consumers?* Ignoring this is the failure mode that quietly kills
event-sourced and event-driven systems years after launch.

Requirements:

- Producers and consumers must evolve independently.
- A consumer written today must keep working when the producer adds a field tomorrow.
- Old events already on a topic must remain readable.
- Schemas must be discoverable and centrally governed, not copy-pasted between repos.

## Decision

Serialize integration events with **Apache Avro** and register schemas in the **Confluent
Schema Registry**, enforcing **`BACKWARD` compatibility** on each subject.

`BACKWARD` compatibility means a consumer using the *new* schema can read data written with
the *old* schema. In practice this constrains evolution to safe changes:

- ✅ Add a field **with a default**.
- ✅ Remove a field that previously had a default.
- ❌ Rename a field, change its type, or add a required field without a default (breaking — requires a new event version/subject).

For changes that cannot be made backward-compatible, we introduce a **new event version**
(e.g. `OrderConfirmed.v2`) and run both until consumers migrate. **Upcasters** (planned, not
implemented) would translate old
on-the-wire versions into the current in-memory representation when reading the internal
event store, so domain code only ever deals with the latest shape.

Every event also carries a **metadata envelope** (`eventId`, `eventType`, `schemaVersion`,
`occurredAt`, `correlationId`) — see the [event catalog](../architecture/event-catalog.md).
`causationId` is reserved on the event store but is not part of the Avro contract and is not written.

## Consequences

**Positive**

- Producers and consumers deploy independently and safely.
- Avro is compact and the JVM/Kafka ecosystem support is first-class.
- The registry is a single source of truth for contracts; CI can fail a build that breaks compatibility.

**Negative / trade-offs**

- Adds Schema Registry as an operational dependency.
- Avro tooling (code generation, build plugins) adds build complexity versus plain JSON.
- Developers must learn compatibility rules — a learning-curve cost, mitigated by CI checks.

## Alternatives considered

- **Plain JSON, no registry (rejected):** zero governance, no compatibility enforcement, schema drift
  is discovered in production. Fine for a toy, disqualifying for "enterprise."
- **Protobuf + registry (viable alternative):** equally good technically and arguably better tooling for
  polyglot consumers. Chose Avro for its tight Kafka/Confluent integration and because dynamic schema
  resolution suits event payloads well. The serialization choice is isolated behind the serializer
  config, so this is reversible.
- **JSON Schema + registry (considered):** human-readable and registry-supported, but larger payloads and
  weaker codegen on the JVM than Avro.
