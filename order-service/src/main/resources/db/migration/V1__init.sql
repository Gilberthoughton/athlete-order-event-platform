-- Event store, transactional outbox, and read-model projections for the order service.
-- See docs/architecture/data-model.md for the rationale behind each constraint.

-- ---------------------------------------------------------------------------
-- Event store (authoritative, append-only)
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    global_position BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    aggregate_id    UUID        NOT NULL,
    aggregate_type  TEXT        NOT NULL,
    sequence_no     BIGINT      NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL,
    payload         JSONB       NOT NULL,
    correlation_id  UUID        NOT NULL,
    causation_id    UUID,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_events_aggregate_sequence UNIQUE (aggregate_id, sequence_no)
);

CREATE INDEX idx_events_aggregate ON events (aggregate_id, sequence_no);
CREATE INDEX idx_events_type ON events (event_type);

-- ---------------------------------------------------------------------------
-- Transactional outbox (written in the same tx as events; drained by the relay)
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    aggregate_id    UUID        NOT NULL,
    topic           TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL,
    payload         JSONB       NOT NULL,
    correlation_id  UUID        NOT NULL,
    causation_id    UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_undispatched ON outbox (created_at) WHERE dispatched_at IS NULL;

-- ---------------------------------------------------------------------------
-- Read models (CQRS) — rebuildable by replaying the event stream
-- ---------------------------------------------------------------------------
CREATE TABLE rm_order_summary (
    order_id      UUID PRIMARY KEY,
    athlete_id    UUID          NOT NULL,
    status        TEXT          NOT NULL,
    total_amount  NUMERIC(12,2) NOT NULL,
    currency      CHAR(3)       NOT NULL,
    line_count    INT           NOT NULL,
    placed_at     TIMESTAMPTZ   NOT NULL,
    last_event_at TIMESTAMPTZ   NOT NULL,
    last_position BIGINT        NOT NULL
);

CREATE INDEX idx_rm_order_summary_athlete ON rm_order_summary (athlete_id, placed_at DESC);

CREATE TABLE projection_checkpoints (
    projection_name TEXT PRIMARY KEY,
    last_position   BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
