-- Per #19: a durable, resumable checkpoint for batch recalculation of a project's customers under
-- one attribution model version. Mirrors the resumable-backfill shape already established for
-- Stripe (V3's sync_checkpoint / StripeBackfillCheckpoint, #12) but keeps the checkpoint in its own
-- table rather than a JSON column: unlike a Stripe connection, a project has no single natural
-- "owner" row to carry it, and every field here (cursor, counters, status) is queried directly by
-- operators/tests rather than only round-tripped through one service.
--
-- Scope is (workspace_id, project_id, model_version), matching AttributionApplicationService's
-- existing per-project recalculation call and ADR-0005's model-version contract: a model-version
-- change gets its own run and its own full sweep, while the prior version's run row (and every
-- derived result it produced) stays untouched -- this table never deletes or rewrites a completed
-- run, only advances the active one.
CREATE TABLE attribution_recalculation_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    cursor_customer_id VARCHAR(255),
    customers_processed BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_attribution_recalculation_run UNIQUE (workspace_id, project_id, model_version),
    CONSTRAINT fk_attribution_recalculation_run_project FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_attribution_recalculation_run_status CHECK (status IN ('RUNNING', 'COMPLETED')),
    CONSTRAINT chk_attribution_recalculation_run_completed CHECK (
        (status = 'COMPLETED') = (completed_at IS NOT NULL)
    ),
    CONSTRAINT chk_attribution_recalculation_run_processed CHECK (customers_processed >= 0)
);

-- Supports the batch job's candidate-customer query, which unions currently-linked customers with
-- customers this project has already recorded an attribution result for (see
-- AttributionRecalculationService) -- both filtered by project and ordered by stripe_customer_id for
-- deterministic cursor pagination.
CREATE INDEX idx_stripe_customer_links_project_active_customer
    ON stripe_customer_links (workspace_id, project_id, stripe_customer_id) WHERE superseded_at IS NULL;
