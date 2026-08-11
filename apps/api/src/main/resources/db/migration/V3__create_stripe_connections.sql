-- Per ADR-0003, no per-workspace OAuth token is ever stored here. This table
-- holds only non-secret connection metadata; all Stripe API calls use the
-- centrally configured platform secret key (never a workspace row) with the
-- Stripe-Account header.
CREATE TABLE stripe_connections (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL UNIQUE REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_account_id VARCHAR(255) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    granted_scope VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    verification_status VARCHAR(16) NOT NULL,
    -- Reserved for the resumable backfill cursor implemented in #12; unused until then.
    sync_checkpoint TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    connected_at TIMESTAMPTZ NOT NULL,
    disconnected_at TIMESTAMPTZ,
    last_verified_at TIMESTAMPTZ,
    last_verification_failed_at TIMESTAMPTZ,
    CONSTRAINT chk_stripe_connections_mode CHECK (mode IN ('TEST', 'LIVE')),
    -- Per ADR-0003, V1 only ever requests/grants read_only. Widening this requires a new ADR and migration.
    CONSTRAINT chk_stripe_connections_scope CHECK (granted_scope = 'read_only'),
    CONSTRAINT chk_stripe_connections_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'DISCONNECTED', 'REVOKED')),
    CONSTRAINT chk_stripe_connections_verification_status
        CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED', 'FAILED'))
);

-- A Stripe account can move to a different workspace after a prior connection is
-- disconnected/revoked, so uniqueness only holds while a connection is live.
CREATE UNIQUE INDEX uq_stripe_connections_active_account
    ON stripe_connections (stripe_account_id) WHERE status IN ('PENDING', 'ACTIVE');

-- Ephemeral, single-use OAuth CSRF state. Only the SHA-256 hash of the random
-- state value is stored, mirroring project_ingestion_keys' hashed-secret pattern.
CREATE TABLE stripe_oauth_states (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    subject_id VARCHAR(255) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    state_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT chk_stripe_oauth_states_mode CHECK (mode IN ('TEST', 'LIVE')),
    CONSTRAINT chk_stripe_oauth_states_hash CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_stripe_oauth_states_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_stripe_oauth_states_consumption
        CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX idx_stripe_oauth_states_workspace ON stripe_oauth_states (workspace_id);
