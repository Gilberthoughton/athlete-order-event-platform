# Athlete Order Event Platform

An **event-sourced**, **event-driven** order management platform for high-throughput
omnichannel retail. Every order's state is an immutable, replayable stream of events;
downstream contexts (inventory, fulfillment, notifications, analytics) integrate through
versioned events on Kafka, published reliably with a transactional outbox.

> **Project status:** Phase 2 — verified end to end. The architecture is documented under
> [`docs/`](docs/) (10 ADRs + C4/domain views); the buildable `order-service` and
> `inventory-consumer` modules implement the event-sourced order core, transactional outbox
> with a polling relay, the confirmation saga, projections, and an idempotent downstream
> consumer. **26 tests pass** — 19 unit + 7 Testcontainers integration tests that exercise the
> real Postgres + Kafka + outbox path (see [Verified with Testcontainers](#verified-with-testcontainers)).

---

## Why this exists

Order management is the hardest, highest-stakes workflow in commerce: it touches money,
inventory, and customer trust, under bursty seasonal load, across multiple fulfillment
channels. This platform models that domain the way a production retail system must:

- **Immutable order history** — an append-only event store is the system of record; nothing
  is ever updated in place, so the full history of every order is permanent and auditable.
- **Replay** — current state (an aggregate, or a read model) is *derived* by folding events,
  so any view can be rebuilt, and any order can be reconstructed as it was at a point in time.
- **Event-driven integration** — other services react to order events asynchronously rather
  than being called synchronously, keeping the order service available under downstream failure.
- **High throughput** — per-order partitioning, read/write separation (CQRS), and idempotent
  consumers allow horizontal scaling; throughput is validated with a published load test.

## Architecture at a glance

```mermaid
flowchart LR
    client["Clients\n(web / mobile / CSR)"] -->|commands + queries| api["Order Service\n(Spring Boot)"]
    api -->|append events + outbox\n(one transaction)| pg[("PostgreSQL\nevent store · outbox · read models")]
    relay["Relay (outbox -> Kafka)"] --> kafka[["Kafka\nintegration events"]]
    pg --> relay
    kafka --> downstream["Inventory · Fulfillment\nNotifications · Analytics"]
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
| **Java 21 + Spring Boot 3** | Order service | Mature JVM concurrency, virtual threads, first-class Kafka/JPA/observability support. |
| **PostgreSQL** | Event store, outbox, read models | ACID appends, unique-constraint concurrency control, partitioning, operational familiarity. |
| **Apache Kafka (KRaft)** | Integration backbone | Durable, partitioned, replayable transport for the public event contract; single-node KRaft (no ZooKeeper) for a light local boot. |
| **JSON event envelope** *(Phase 1–2)* | Wire contract | Self-describing `eventType` + `schemaVersion` + payload; registry-free so the stack boots from one `compose.yaml`. Avro + Schema Registry is the documented target ([ADR 0004](docs/adr/0004-avro-schema-registry-backward-compat.md)). |
| **Flyway** | Schema migrations | Versioned, reviewable database changes. |
| **Docker / Docker Compose** | Local orchestration | `docker compose up` boots the full stack for development and the demo. |
| **Testcontainers** | Integration testing | Tests run against real Postgres and Kafka, not mocks. |
| **Micrometer · Prometheus · Grafana · OpenTelemetry** | Observability | Metrics, dashboards, and distributed traces correlated with event IDs. |
| **Gatling / k6** | Load testing | Reproducible throughput and latency measurement. |

## Roadmap

Implementation proceeds in phases so each layer is provable before the next is added:

- **Phase 0 — Foundations** *(done)*: 10 ADRs, C4 + domain docs, event catalog, data model.
- **Phase 1 — Event-sourced core + event-driven slice** *(current)*: `Order` aggregate,
  Postgres event store with optimistic concurrency, transactional outbox + polling relay,
  `OrderConfirmationSaga`, read-model projection, command/query REST API, and a separate
  idempotent `inventory-consumer` — with Given-When-Then and fake-driven tests.
- **Phase 2 — Integration verification** *(done)*: Testcontainers integration tests proving the
  real Postgres + Kafka + outbox path end to end — order lifecycle, outbox atomicity/reliability,
  projection replay/rebuild, and consumer idempotency. *(Still ahead: API depth, point-in-time
  replay endpoints, fulfillment/return events.)*
- **Phase 3 — Contract hardening**: Avro + Schema Registry, contract tests, CI compatibility checks.
- **Phase 4 — Observability**: Micrometer/Prometheus/Grafana, OpenTelemetry tracing,
  structured logging, health/readiness probes, consumer-lag dashboards.
- **Phase 5 — Load test + hardening**: Gatling/k6 scenario with published methodology and
  measured numbers; tuning; runbook.

## Testing strategy

Two tiers, kept separate so the fast tier needs no Docker:

- **Unit tests (`./gradlew test`, no Docker):** Given-When-Then aggregate tests (*given* prior
  events, *when* a command, *then* assert the emitted events/rejection) over the pure, I/O-free
  domain; the confirmation saga driven over in-memory fakes (happy path, payment-decline,
  out-of-stock compensation, transient-failure retry, idempotent re-drive); and consumer
  idempotency over fakes. **19 tests.**
- **Integration tests (`./gradlew integrationTest`, requires Docker):** the real
  Postgres + Kafka path via Testcontainers. **7 tests** — see below.

### Verified with Testcontainers

These run the actual services against real PostgreSQL 16 and Kafka containers — not mocks —
and all pass:

| Test | What it proves |
|------|----------------|
| `OrderLifecycleIT` | REST `POST /api/orders` → domain events persisted to Postgres → integration event written to the outbox → dispatched by the polling relay → **record observed on the Kafka topic** → read-model projection updated to `CONFIRMED`. |
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
- **Not yet built (see roadmap):** Avro + Schema Registry (integration events are JSON for now),
  observability (metrics/traces/dashboards), and a load test with measured throughput. The relay
  is a polling publisher; Debezium CDC is documented as the production upgrade. This is a
  reference implementation, not a production deployment.

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

## Local development

```bash
docker compose up -d                       # PostgreSQL (orders + fulfillment dbs) + Kafka (KRaft)
./gradlew :order-service:bootRun           # start the order service        (http://localhost:8080)
./gradlew :inventory-consumer:bootRun      # start the downstream consumer   (http://localhost:8081)
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
# The inventory-consumer's read model reflects the outcome at GET localhost:8081/api/fulfillment/orders.
```

## Project structure

```
athlete-order-event-platform/
├── docs/                          # 10 ADRs + C4 / domain / event-catalog / data-model
├── compose.yaml                   # PostgreSQL + Kafka (KRaft)
├── settings.gradle.kts            # two modules
├── order-service/                 # event-sourced order lifecycle (Spring Boot)
│   └── src/main/java/com/athlete/order/
│       ├── domain/                # Order aggregate, value objects, domain events (pure, no framework)
│       ├── application/           # command handlers, OrderConfirmationSaga, ports
│       ├── infrastructure/        # JDBC event store + outbox, polling relay, projection, Kafka, stubs
│       └── api/                   # REST controllers (commands + queries)
└── inventory-consumer/            # idempotent downstream consumer + read model (Spring Boot)
    └── src/main/java/com/athlete/inventory/
```
