-- Per ADR-0002/ADR-0003 and ARCHITECTURE.md's reliability rules: the webhook endpoint verifies
-- Stripe's signature against the unmodified request body and durably persists the raw event
-- before acknowledging it. This table is append-only for the payload itself; only the
-- processing/retry/replay bookkeeping columns are ever updated after insert, and only by the
-- future normalization worker (#13) -- this migration's own module (#11) never updates a row
-- after inserting it.
CREATE TABLE stripe_webhook_events (
    id UUID PRIMARY KEY,
    stripe_event_id VARCHAR(255) NOT NULL,
    stripe_account_id VARCHAR(255) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    -- Nullable: no live connection matched stripe_account_id at receipt time (unknown or
    -- disconnected/revoked account). Per ADR-0003, such an event is stored as an orphaned raw
    -- record and is never processed into a workspace.
    connection_id UUID REFERENCES stripe_connections (id) ON DELETE SET NULL,
    workspace_id UUID REFERENCES workspaces (id) ON DELETE SET NULL,
    event_type VARCHAR(255) NOT NULL,
    api_version VARCHAR(32),
    stripe_created_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- The exact bytes verified against the Stripe-Signature header -- the only source of truth for
    -- what Stripe actually sent and signed. Never reconstructed from `payload`, which is a parsed
    -- JSONB mirror kept only for future query convenience (#13) and can differ byte-for-byte
    -- (key order, whitespace, number formatting) from what was originally received and verified.
    raw_payload BYTEA NOT NULL,
    payload JSONB NOT NULL,
    processing_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- Reserved for the future normalization worker (#13); always 0/NULL out of #11.
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    last_error TEXT,
    -- Reserved for the future replay/recalculation tooling (Phase 4); always 0/NULL out of #11.
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Scoped by (mode, stripe_event_id) rather than stripe_event_id alone: test-mode and live-mode
    -- are separate Stripe environments (separate signing secrets, separate object graphs), and
    -- uniqueness/lookup must not conflate them even though a collision is very unlikely in practice.
    CONSTRAINT uq_stripe_webhook_events_mode_event_id UNIQUE (mode, stripe_event_id),
    CONSTRAINT chk_stripe_webhook_events_mode CHECK (mode IN ('TEST', 'LIVE')),
    CONSTRAINT chk_stripe_webhook_events_processing_state
        CHECK (processing_state IN ('PENDING', 'ORPHANED', 'PROCESSED', 'FAILED')),
    -- Exactly the routing invariant the ingestion service implements: an event is ORPHANED iff no
    -- live connection was matched, in which case it carries no connection/workspace link.
    CONSTRAINT chk_stripe_webhook_events_orphan_consistency
        CHECK ((processing_state = 'ORPHANED') = (connection_id IS NULL AND workspace_id IS NULL)),
    CONSTRAINT chk_stripe_webhook_events_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_stripe_webhook_events_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_stripe_webhook_events_replay_count CHECK (replay_count >= 0)
);

CREATE INDEX idx_stripe_webhook_events_account ON stripe_webhook_events (stripe_account_id);
CREATE INDEX idx_stripe_webhook_events_workspace ON stripe_webhook_events (workspace_id);
CREATE INDEX idx_stripe_webhook_events_processing_state ON stripe_webhook_events (processing_state);
