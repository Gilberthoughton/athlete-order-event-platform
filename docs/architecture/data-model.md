# Data Model

The PostgreSQL schema holds three clearly separated concerns: the **event store** (truth),
the **outbox** (reliable publishing), and **read models** (CQRS query views). This document
describes the tables and the key constraints that enforce the architecture's guarantees.
It is a design reference; concrete migrations (Flyway) land in Phase 1.

## Event store

The append-only system of record. Rows are **never updated or deleted** (cold partitions may
be archived, never mutated).

```sql
CREATE TABLE events (
    global_position BIGSERIAL PRIMARY KEY,      -- global append order (for projections)
    event_id        UUID        NOT NULL UNIQUE,
    aggregate_id    UUID        NOT NULL,         -- the orderId
    aggregate_type  TEXT        NOT NULL,         -- 'Order'
    sequence_no     BIGINT      NOT NULL,         -- per-aggregate version, starts at 0
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL,
    payload         JSONB       NOT NULL,         -- domain event body
    correlation_id  UUID        NOT NULL,
    causation_id    UUID,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Optimistic concurrency: two writers cannot both append the same next version.
    CONSTRAINT uq_aggregate_sequence UNIQUE (aggregate_id, sequence_no)
);

CREATE INDEX idx_events_aggregate ON events (aggregate_id, sequence_no);  -- fast rehydration
CREATE INDEX idx_events_type      ON events (event_type);                  -- projections by type
```

- **`uq_aggregate_sequence`** is the linchpin of [ADR 0005](../adr/0005-optimistic-concurrency-control.md):
  appending at an already-taken version fails, surfacing a concurrency conflict for retry.
- **`global_position`** gives projections a stable, total order to consume in, independent of
  per-aggregate sequencing.
- Planned: **range partitioning by `occurred_at`** (monthly) so hot data stays small and cold
  partitions can be archived; `global_position` remains globally monotonic.
- `payload` is `JSONB` in the store for queryability; the *public* Kafka representation is Avro
  ([ADR 0004](../adr/0004-avro-schema-registry-backward-compat.md)).

## Outbox

Written in the **same transaction** as the events it corresponds to
([ADR 0002](../adr/0002-transactional-outbox-for-publishing.md)).

```sql
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,  -- id of the integration event
    aggregate_id    UUID        NOT NULL,         -- partition key (orderId) for Kafka
    topic           TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL,
    payload         JSONB       NOT NULL,         -- integration event body (Avro-encoded on publish)
    correlation_id  UUID        NOT NULL,
    causation_id    UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at   TIMESTAMPTZ                    -- NULL until the relay publishes
);

-- Polling relay reads undispatched rows without contending across workers.
CREATE INDEX idx_outbox_undispatched ON outbox (created_at) WHERE dispatched_at IS NULL;
```

- Polling relay query: `SELECT ... WHERE dispatched_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED`.
- With **Debezium CDC**, the relay tails inserts instead of polling; the table shape is unchanged.
- A retention job removes dispatched rows older than a window (the events table remains the permanent record).

## Read models (projections / CQRS)

Query-optimized, **eventually consistent**, and fully **rebuildable** by replaying `events`.
They may be dropped and rebuilt at any time — they hold no truth.

```sql
-- Current status view for fast order lookups and the tracking UI.
CREATE TABLE rm_order_summary (
    order_id        UUID PRIMARY KEY,
    athlete_id      UUID        NOT NULL,
    status          TEXT        NOT NULL,         -- derived: PLACED/CONFIRMED/SHIPPED/...
    total_amount    NUMERIC(12,2) NOT NULL,
    currency        CHAR(3)     NOT NULL,
    line_count      INT         NOT NULL,
    placed_at       TIMESTAMPTZ NOT NULL,
    last_event_at   TIMESTAMPTZ NOT NULL,
    last_position   BIGINT      NOT NULL          -- global_position consumed (resume point)
);

CREATE INDEX idx_rm_order_summary_athlete ON rm_order_summary (athlete_id, placed_at DESC);

-- Projection bookkeeping: where each projection has consumed up to (for resumable rebuilds).
CREATE TABLE projection_checkpoints (
    projection_name TEXT PRIMARY KEY,
    last_position   BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- A projection worker reads `events` ordered by `global_position` from its checkpoint, folds
  each event into the read model, and advances the checkpoint **idempotently** (upsert by key).
- Rebuild = reset the checkpoint to 0 (and truncate/rebuild a new table version) and re-run.

## Consumer-side inbox (downstream services)

For the sample downstream consumer (e.g. inventory), the **processed-events** table enforces
idempotency ([ADR 0008](../adr/0008-idempotent-consumers.md)):

```sql
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,                -- duplicate delivery -> PK conflict -> skip
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## How the tables enforce the architecture

| Guarantee | Enforced by |
|-----------|-------------|
| Immutable history | Append-only `events`; no UPDATE/DELETE in application code. |
| No lost events | `events` + `outbox` written in one transaction. |
| No lost updates | `uq_aggregate_sequence` unique constraint. |
| Ordered publish per order | `outbox.aggregate_id` → Kafka key. |
| Rebuildable reads | `global_position` + `projection_checkpoints`. |
| Effectively-once processing | `processed_events` PK + idempotent upserts. |
