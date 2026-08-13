-- Durable, resumable checkpoint for a full project tracking-data wipe (#8) -- the same
-- fetch-a-bounded-slice / apply / advance-checkpoint shape as StripeBackfillCheckpoint (#12) and
-- attribution_recalculation_runs (#19), scoped to one project rather than a JSON column since a
-- project has several distinct table phases to sweep in dependency order rather than one paginated
-- upstream resource. Exactly one lifetime row per project: re-running ProjectDataDeletionService
-- after status = 'COMPLETED' is a no-op (idempotent); ProjectDataDeletionService#restart resets it
-- to sweep again from the beginning, e.g. because the project received new tracking data afterward.
--
-- The VERIFICATION phase clears tracking_verification_attempts (V13) -- project-scoped installation
-- diagnostics, not evidence anything else depends on, so it is always fully deletable.
--
-- Deletion never force-removes touchpoints (or the visitors/sessions that reach them) still
-- referenced by customer_attribution_results (first_touchpoint_id / last_touchpoint_id, ON DELETE
-- RESTRICT per V10), nor the visitor_aliases and external_identities a still-Stripe-linked identity
-- needs to remain re-derivable by a future attribution recalculation (see
-- IdentityLinkingService#deleteIdentityDataBatch) -- those rows are skipped in place, preserving
-- derived attribution history and safe recalculation per ARCHITECTURE.md's immutable-inputs/
-- derived-results contract. skipped_evidence_rows reports how many were left behind so a run's status
-- is honestly inspectable rather than silently claiming a full wipe.
CREATE TABLE project_data_deletion_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    rows_deleted BIGINT NOT NULL DEFAULT 0,
    skipped_evidence_rows BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_deletion_run_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_deletion_run_status CHECK (status IN ('RUNNING', 'COMPLETED')),
    CONSTRAINT chk_deletion_run_phase CHECK (phase IN (
        'EVENTS', 'BATCHES', 'FAILURE_DIAGNOSTICS', 'VERIFICATION', 'IDENTITY', 'TOUCHPOINTS', 'SESSIONS',
        'VISITORS', 'DONE')),
    CONSTRAINT chk_deletion_run_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT chk_deletion_run_rows CHECK (rows_deleted >= 0 AND skipped_evidence_rows >= 0)
);
