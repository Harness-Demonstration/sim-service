CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ
);

CREATE INDEX outbox_pending_idx ON outbox (created_at, id) WHERE sent_at IS NULL;
