-- Downstream consumer schema: an inbox for idempotency and the fulfillment read model.

-- Consumer-side idempotency (ADR 0008): a duplicate eventId hits the primary key and is skipped.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Read model maintained from order integration events.
CREATE TABLE fulfillment_order (
    order_id     UUID PRIMARY KEY,
    status       TEXT          NOT NULL,
    total_amount NUMERIC(12,2),
    currency     CHAR(3),
    note         TEXT,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_fulfillment_order_status ON fulfillment_order (status, updated_at DESC);
