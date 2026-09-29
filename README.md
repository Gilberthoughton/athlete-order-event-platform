# Athlete Order Event Platform

An **event-sourced**, **event-driven** order management platform for high-throughput
omnichannel retail. Every order's state is an immutable, replayable stream of events;
downstream contexts (inventory, fulfillment, notifications, analytics) integrate through
versioned events on Kafka, published reliably with a transactional outbox.

> **Project status:** Phase 4 — governed contracts, observable, verified end to end. The architecture
> is documented under [`docs/`](docs/) (10 ADRs + C4/domain views); the buildable `order-service` and
> `inventory-consumer` modules implement the event-sourced order core, transactional outbox with a
> polling relay, the confirmation saga, projections, an idempotent **Avro** consumer, **Schema-Registry
> governed event contracts** (see [Event Contract Governance](#event-contract-governance)), and full
> observability (Prometheus metrics, structured JSON logs, correlation IDs, health probes — see
> [Observability](#observability)). **40 tests pass** — 27 unit + 13 Testcontainers integration tests
> that exercise the real Postgres + Kafka + Avro + outbox path (see [Verified with Testcontainers](#verified-with-testcontainers)).

---

## Why this project exists

Order management is the highest-stakes workflow in omnichannel retail: it moves money, commits
inventory, and carries customer trust — under bursty, seasonal load across many fulfillment
channels (ship, BOPIS, ship-from-store). This repository is a focused, production-shaped reference
for that problem, built to demonstrate three things an enterprise commerce team cares about:

- **A trustworthy order lifecycle.** The order is event-sourced: an append-only, immutable history
  is the system of record, every state change is auditable, and any view can be rebuilt by replaying
  events — even reconstructing an order as it was at a point in time.
- **Event-driven integration, done safely.** Downstream contexts react to versioned events over
  Kafka — never synchronous calls — published with a transactional outbox (no dual-write data loss)
  and governed by a schema registry so contracts evolve without breaking consumers.
- **Reliability under failure.** At-least-once delivery with idempotent consumers, optimistic
  concurrency, a saga with compensation, and per-order ordering keep the system correct when
  payments, inventory, or downstream services misbehave.

It is a reference implementation, not a production deployment — the
[Verified with Testcontainers](#verified-with-testcontainers) section states exactly what is
proven against real infrastructure versus simulated.

## Architecture at a glance

```mermaid
flowchart LR
    client["Clients<br/>(web / mobile / CSR)"] -->|commands + queries| api["Order Service<br/>(Spring Boot)"]
    api -->|append events + outbox<br/>(one transaction)| pg[("PostgreSQL<br/>event store · outbox · read models")]
    relay["Relay (outbox -> Kafka)"] --> kafka[["Kafka<br/>integration events"]]
    pg --> relay
    kafka --> downstream["Inventory · Fulfillment<br/>Notifications · Analytics"]
    kafka --- registry["Schema Registry (Avro)"]
```

Full views (C4 context/container/component, runtime sequences, quality attributes) are in
[`docs/architecture/overview.md`](docs/architecture/overview.md).

## Key engineering decisions

The non-obvious decisions are recorded as ADRs, each with the alternatives that were rejected
and why ([index](docs/adr/README.md)):

| # | Decision |
|---|----------|
| [0001](docs/adr/0001-postgres-as-authoritative-event-store.md) | PostgreSQL is the authoritative event store; Kafka is the integration backbone |
| [0002](docs/adr/0002-transactional-outbox-for-publishing.md) | Reliable publishing via a transactional outbox (no dual writes) |
| [0003](docs/adr/0003-separate-domain-and-integration-events.md) | Internal domain events kept separate from public integration events |
| [0004](docs/adr/0004-avro-schema-registry-backward-compat.md) | Avro + Schema Registry, BACKWARD compatibility for event evolution |
| [0005](docs/adr/0005-optimistic-concurrency-control.md) | Optimistic concurrency via `(aggregate_id, sequence_no)` |
| [0006](docs/adr/0006-defer-aggregate-snapshots.md) | Snapshots deliberately deferred — orders are bounded |
| [0007](docs/adr/0007-partition-by-order-id.md) | Partition by `orderId` for per-aggregate ordering |
| [0008](docs/adr/0008-idempotent-consumers.md) | At-least-once delivery + idempotent consumers |
| [0009](docs/adr/0009-saga-for-order-confirmation.md) | Order confirmation modeled as a lightweight saga / process manager |
| [0010](docs/adr/0010-module-topology.md) | Two modules: `order-service` + `inventory-consumer` |

## Domain

Modeled in the language of omnichannel sporting-goods retail — athletes, orders, SKUs,
BOPIS / ship-from-store fulfillment, split shipments, partial fulfillment, cancellations,
and returns. See the [domain glossary](docs/architecture/domain-glossary.md),
[event catalog](docs/architecture/event-catalog.md), and
[order lifecycle](docs/architecture/order-lifecycle.md). The data model and the constraints
that enforce each guarantee are in [`docs/architecture/data-model.md`](docs/architecture/data-model.md).

## Technology

| Technology | Role | Rationale |
|------------|------|-----------|
| **Java 21 + Spring Boot 3** | Order service | Mature JVM concurrency, first-class Kafka/JDBC/observability support. |
| **PostgreSQL** | Event store, outbox, read models | ACID appends, unique-constraint concurrency control, partitioning, operational familiarity. |
| **Apache Kafka (KRaft)** | Integration backbone | Durable, partitioned, replayable transport for the public event contract; single-node KRaft (no ZooKeeper) for a light local boot. |
| **Avro + Confluent Schema Registry** | Wire contract + governance | Integration events are Avro, governed by the registry with `BACKWARD` compatibility; schemas evolve safely and breaking changes fail the build ([ADR 0004](docs/adr/0004-avro-schema-registry-backward-compat.md), [Event Contract Governance](#event-contract-governance)). |
| **Flyway** | Schema migrations | Versioned, reviewable database changes. |
| **Docker / Docker Compose** | Local orchestration | `docker compose up` boots the full stack for development and the demo. |
| **Testcontainers** | Integration testing | Tests run against real Postgres and Kafka, not mocks. |
| **Micrometer · Prometheus · Grafana** | Observability | Domain metrics, dashboards, structured JSON logs, and request/event correlation IDs (distributed tracing is a documented next step, not yet wired). |

## Roadmap

Implementation proceeds in phases so each layer is provable before the next is added:

- **Phase 0 — Foundations** *(done)*: 10 ADRs, C4 + domain docs, event catalog, data model.
- **Phase 1 — Event-sourced core + event-driven slice** *(done)*: `Order` aggregate,
  Postgres event store with optimistic concurrency, transactional outbox + polling relay,
  `OrderConfirmationSaga`, read-model projection, command/query REST API, and a separate
  idempotent `inventory-consumer` — with Given-When-Then and fake-driven tests.
- **Phase 2 — Integration verification** *(done)*: Testcontainers integration tests proving the
  real Postgres + Kafka + outbox path end to end — order lifecycle, outbox atomicity/reliability,
  projection replay/rebuild, and consumer idempotency. *(Still ahead: API depth, point-in-time
  replay endpoints, fulfillment/return events.)*
- **Phase 3 — Observability** *(done)*: Micrometer → Prometheus metrics for the order lifecycle,
  saga, outbox, projection, and consumer; structured JSON logging with request/event correlation
  IDs; liveness/readiness probes; and a provisioned Prometheus + Grafana stack. *(Still ahead:
  OpenTelemetry/OTLP distributed tracing, consumer-lag panels.)*
- **Phase 4 — Contract hardening** *(done)*: Avro integration events governed by the Confluent
  Schema Registry with `BACKWARD` compatibility; consumer deserializes Avro; a build-time
  compatibility test fails fast on breaking changes. See [Event Contract Governance](#event-contract-governance).
- **Phase 5 — Load test + hardening**: Gatling/k6 scenario with published methodology and
  measured numbers; tuning; runbook.

## Testing strategy

Two tiers, kept separate so the fast tier needs no Docker:

- **Unit tests (`./gradlew test`, no Docker):** Given-When-Then aggregate tests (*given* prior
  events, *when* a command, *then* assert the emitted events/rejection) over the pure, I/O-free
  domain; the confirmation saga driven over in-memory fakes (happy path, payment-decline,
  out-of-stock compensation, transient-failure retry, idempotent re-drive); consumer idempotency
  over fakes; Avro serde round-trips; and schema-compatibility checks. **25 tests.**
- **Integration tests (`./gradlew integrationTest`, requires Docker):** the real
  Postgres + Kafka + Avro path via Testcontainers. **9 tests** — see below.

### Verified with Testcontainers

These run the actual services against real PostgreSQL 16 and Kafka containers — not mocks —
and all pass:

| Test | What it proves |
|------|----------------|
| `OrderLifecycleIT` | REST `POST /api/orders` → domain events persisted to Postgres → integration event written to the outbox → serialized to **Avro** and dispatched by the polling relay → **Avro record observed on the Kafka topic** → read-model projection updated to `CONFIRMED`. |
| `ObservabilityIT` (×2) | Liveness/readiness probes report UP; `/actuator/prometheus` exposes the domain metrics after traffic. |
| `OutboxReliabilityIT` (×2) | A successful publish marks the outbox row dispatched and the record reaches Kafka; a publish against an unreachable broker **leaves the row undispatched (retryable)**, and a healthy relay then dispatches it. |
| `OutboxAtomicityIT` | When the outbox write fails, the domain-event append in the same transaction is **rolled back** — proving event store + outbox commit atomically (no dual write). |
| `ProjectionRebuildIT` | The read model is cleared and **rebuilt deterministically by replaying** the event stream from position 0. |
| `ConsumerIT` (×2) | `inventory-consumer` consumes an integration event from Kafka, updates its read model, and **ignores a duplicate delivery** (idempotency); a cancellation event marks the order cancelled. |

The producer suite (order-service) and the consumer suite (inventory-consumer) are independent
and meet at the Kafka wire contract — the realistic way to test two separately deployable services.

**Honest scope — what is and isn't covered:**

- **Verified against real infrastructure:** event sourcing + optimistic-concurrency append,
  the transactional outbox + polling relay, at-least-once publish to Kafka, idempotent
  consumption, and projection replay/rebuild.
- **Simulated (stubs):** the payment gateway and inventory allocator are deterministic in-process
  stubs (`StubPaymentGateway`, `StubInventoryAllocator`) — the saga orchestration, retry, and
  compensation around them are real; the external systems are not.
- **Not yet built (see roadmap):** a load test with measured throughput, and distributed tracing
  (OpenTelemetry/OTLP spans). The relay is a polling publisher; Debezium CDC is documented as the
  production upgrade. This is a reference implementation, not a production deployment.

### Running the integration tests

A running Docker daemon is required.

```bash
./gradlew integrationTest          # Docker Desktop: works as-is
```

On **Colima** (or other non-Docker-Desktop runtimes) the daemon socket and host differ, so export:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_HOST_OVERRIDE="$(colima ls -j | python3 -c 'import sys,json;print(json.load(sys.stdin)["address"])')"
./gradlew integrationTest
```

(The build pins the Docker API version to 1.40+ for the test JVM, which modern daemons require.)

## Event Contract Governance

Integration events are a **public contract** consumed by independently deployed services, so they
are governed — not hand-rolled JSON.

**Avro schema.** The contract is a single flat Avro record,
[`order-integration-event.avsc`](order-service/src/main/avro/order-integration-event.avsc): stable
metadata (`eventId`, `eventType`, `aggregateId`, `correlationId`, `occurredAt`, `schemaVersion`) plus
an `eventType` enum discriminator and nullable, type-specific fields. The Gradle Avro plugin generates
the Java classes at build time (which also validates the schema syntax).

**Schema Registry + BACKWARD compatibility.** The relay serializes with Confluent's
`KafkaAvroSerializer`; the consumer deserializes with `KafkaAvroDeserializer`. A `cp-schema-registry`
runs in `compose.yaml` with compatibility level `BACKWARD` — a new schema must be able to read data
written with the previous one. Avro serialization happens **in the relay, not the order transaction**,
so a registry outage can never block order processing (the outbox write stays registry-free).

**Safe evolution — allowed vs breaking:**

| Change | Backward-compatible? |
|--------|----------------------|
| Add a field with `"default"` (e.g. nullable `["null","string"]`, default `null`) | ✅ allowed |
| Add an enum symbol *(with a default for the enum)* | ✅ allowed |
| Remove a field that had a default | ✅ allowed |
| Add a required field with no default | ❌ breaking |
| Rename a field, or change its type | ❌ breaking |
| Remove/rename an enum symbol in use | ❌ breaking |

**Fail fast in CI.** [`SchemaCompatibilityTest`](order-service/src/test/java/com/athlete/order/contracts/SchemaCompatibilityTest.java)
compares the current contract against a committed baseline of the published schema
([`order-integration-event.v1.avsc`](order-service/src/test/resources/contracts/order-integration-event.v1.avsc))
and fails the build on a breaking change. The check runs on every push via
[GitHub Actions](.github/workflows/ci.yml). It is a build-time Avro check; it does not talk to a
running registry. It also
asserts the BACKWARD rule with Avro's `SchemaCompatibility` API (allowed change → compatible; required
field with no default → incompatible) and that the generated contract is self-compatible. A breaking
edit fails `./gradlew test` before it can reach a running registry.

**Contract-only coupling.** `inventory-consumer` shares **no code** with the producer — it owns its
[own copy of the schema](inventory-consumer/src/main/avro/order-integration-event.avsc) and generates
its own classes (ADR 0003 / ADR 0010). Schema Registry compatibility + Avro schema resolution let the
two evolve independently.

**Verified vs local-demo vs production:**

- **Verified in tests:** real Confluent `KafkaAvroSerializer`/`KafkaAvroDeserializer`, the Avro wire
  format (magic byte + schema id + binary), specific-record deserialization, and round-trips over a
  real Kafka container — run against an **in-JVM mock Schema Registry** (`mock://`), so no registry
  container is needed in CI.
- **Local-demo only:** the real `cp-schema-registry` in `compose.yaml` (compatibility enforcement,
  the registry REST API) is for manual local use, not exercised by the automated tests.
- **Production differences:** disable `auto.register.schemas` and register/evolve schemas through a
  CI pipeline against the central registry; add a registry compatibility-check gate; secure the
  registry (auth/TLS); and consider per-event-type subjects (`RecordNameStrategy`) if the topic
  carries many event types.

## Observability

The platform is instrumented for metrics, structured logs, correlation, and health — viewable
locally with the Prometheus + Grafana services in `compose.yaml`.

**Metrics (Micrometer → Prometheus)** at `/actuator/prometheus` on each service:

| Metric | Meaning |
|--------|---------|
| `aoep_orders_placed_total` / `aoep_orders_confirmed_total` / `aoep_orders_cancelled_total` | Order lifecycle counters. |
| `aoep_saga_completed_total{outcome=confirmed\|cancelled\|failed}` | Confirmation-saga terminal outcomes. |
| `aoep_saga_retries_total` | Transient-failure retries inside the saga. |
| `aoep_outbox_published_total` | Integration events drained from the outbox to Kafka. |
| `aoep_projection_events_applied_total` / `aoep_projection_rebuilds_total` | Read-model progress and full replays. |
| `aoep_consumer_events_received_total` / `_duplicate_total` / `_applied_total` (by `type`) | Downstream consume + idempotency (duplicates ignored). |

**Structured JSON logging** — both services log one JSON object per line (Logback +
logstash-encoder), each carrying a `correlationId`. The order service sets it from an inbound
`X-Correlation-Id` header (or generates one and echoes it back); the consumer adopts the
`correlationId` from the integration-event envelope, so an order's logs correlate across both
services. Read locally with `./gradlew :order-service:bootRun | jq`.

**Health & readiness** — `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`
(Kubernetes-style probes).

### Run the monitoring stack locally

```bash
docker compose up -d                         # includes Prometheus (:9090) and Grafana (:3000)
./gradlew :order-service:bootRun             # exposes /actuator/prometheus on :8080
./gradlew :inventory-consumer:bootRun        # exposes /actuator/prometheus on :8082
# generate some traffic (see the curl below), then:
#   - raw metrics:  curl -s localhost:8080/actuator/prometheus | grep aoep_
#   - Grafana:      http://localhost:3000  -> dashboard "Athlete Order Event Platform — Overview"
#                   (anonymous admin enabled; Prometheus datasource + dashboard auto-provisioned)
```

What's instrumented is real; **distributed tracing (OpenTelemetry/OTLP spans + a collector) is
not yet wired** — the correlation ID is the current tracing primitive. That is a documented next step.

## Local development

```bash
docker compose up -d                       # PostgreSQL + Kafka (KRaft) + Schema Registry (:8081)
./gradlew :order-service:bootRun           # start the order service        (http://localhost:8080)
./gradlew :inventory-consumer:bootRun      # start the downstream consumer   (http://localhost:8082)
./gradlew test                             # unit + fake-driven tests across both modules
```

Place an order and watch it flow through the saga to `CONFIRMED`, get published via the
outbox, and be consumed downstream:

```bash
curl -s localhost:8080/api/orders -H 'content-type: application/json' -d '{
  "athleteId":"6f1d8d2e-0d2a-4f1b-9a3e-2b6a1c0d4e5f",
  "lines":[{"sku":"SHOE-NIKE-PEGASUS-10","quantity":1,"unitPriceAmount":129.99,"currency":"USD","fulfillmentType":"SHIP"}]
}'
# -> 201 Created with an orderId; GET /api/orders/{id} shows status transition to CONFIRMED.
# Failure/compensation paths: an order total >= $5,000 is declined by the payment stub, and any
# SKU prefixed OOS- fails inventory allocation (voiding the payment hold) -> order CANCELLED.
# The inventory-consumer's read model reflects the outcome at GET localhost:8082/api/fulfillment/orders.
```

## Project structure

```
athlete-order-event-platform/
├── docs/                          # 10 ADRs + C4 / domain / event-catalog / data-model
├── compose.yaml                   # PostgreSQL + Kafka (KRaft) + Schema Registry + Prometheus + Grafana
├── monitoring/                    # Prometheus scrape config + Grafana provisioning & dashboard
├── settings.gradle.kts            # two modules
├── order-service/                 # event-sourced order lifecycle (Spring Boot)
│   └── src/main/
│       ├── avro/                  # order-integration-event.avsc (the published wire contract)
│       └── java/com/athlete/order/
│           ├── domain/            # Order aggregate, value objects, domain events (pure, no framework)
│           ├── application/       # command handlers, OrderConfirmationSaga, ports
│           ├── infrastructure/    # JDBC event store + outbox, polling relay, projection, Kafka/Avro, stubs
│           └── api/               # REST controllers (commands + queries)
└── inventory-consumer/            # idempotent downstream Avro consumer + read model (Spring Boot)
    └── src/main/
        ├── avro/                  # consumer-owned copy of the contract
        └── java/com/athlete/inventory/
```

## License

Released under the [MIT License](LICENSE).
