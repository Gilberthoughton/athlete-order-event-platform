# ADR 0004 — Avro + Schema Registry with BACKWARD compatibility for integration events

- **Status:** Accepted (target); **phased** — see "Phased adoption" below
- **Date:** 2026-06-15
- **Deciders:** Platform engineering

> **Phased adoption.** Avro + Schema Registry is the **target** contract format and the
> decision of record for production. To keep the reference implementation bootable from a
> single `compose.yaml` (Postgres + Kafka only, no Schema Registry container), **Phase 1–2
> serialize integration events as a self-describing JSON envelope** (`eventType` +
> `schemaVersion` + payload — see the [event catalog](../architecture/event-catalog.md)).
> The compatibility *discipline* (additive, defaulted fields only) is followed from day one,
> so migrating to Avro is a serialization-layer change behind the existing `MessageRelay` /
> consumer-deserialization seams, not a redesign. Schema Registry + Avro land in the
> production-hardening phase. The rest of this ADR describes that target state.

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
(e.g. `OrderConfirmed.v2`) and run both until consumers migrate. **Upcasters** translate old
on-the-wire versions into the current in-memory representation when reading the internal
event store, so domain code only ever deals with the latest shape.

Every event also carries a **metadata envelope** (`eventId`, `eventType`, `schemaVersion`,
`occurredAt`, `correlationId`, `causationId`) — see the [event catalog](../architecture/event-catalog.md).

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
