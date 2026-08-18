-- Per #62 (accepted contract on #27): workspace lifecycle status plus the durable, resumable,
-- multi-instance-safe checkpoint for owner-only, cross-module workspace deletion. Generalizes the
-- fetch-a-bounded-slice/apply/advance-checkpoint shape already established by
-- project_data_deletion_runs (V16), attribution_recalculation_runs (V11), and StripeBackfillCheckpoint
-- (V3's sync_checkpoint) to a run that spans every module instead of one project or one paginated
-- upstream resource.
--
-- Two lifecycle states only: every workspace is born ACTIVE and stays that way forever unless a
-- deletion request moves it to DELETING, a one-way transition that is never reversed by any API. There
-- is deliberately no DELETED status: the workspace row itself is hard-deleted as the run's final phase
-- (see WorkspaceDeletionRequestService), so "the row does not exist" is what DELETED means.
ALTER TABLE workspaces
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE workspaces
    ADD CONSTRAINT chk_workspaces_status CHECK (status IN ('ACTIVE', 'DELETING'));

-- One lifetime row per workspace, playing two roles depending on status:
--
-- While RUNNING, this is the deletion run's checkpoint: `phase` matches
-- WorkspaceDeletionRequestService's phase enum exactly, and `rows_deleted` accumulates the bulk-delete
-- row count across every phase. The row is read with SELECT ... FOR UPDATE before each batch, exactly
-- like project_data_deletion_runs -- that row lock IS this run's multi-instance-safe lease; no separate
-- lease_owner/lease_expires_at columns are needed because Postgres row locks are visible across every
-- API instance sharing the database, not just the process that acquired them.
--
-- Once COMPLETED, this same row IS the 30-day non-PII tombstone the accepted contract requires: id
-- (the request/tombstone id), workspace_id, status, and timestamps -- and nothing else, because this
-- table never stores a requester subject id, an email, or any billing/customer data in the first
-- place. The confirmation string ("DELETE <workspaceId>") is validated by the controller at request
-- time and never persisted. A scheduled purge (WorkspaceTombstonePurgeService) deletes rows whose
-- completed_at is more than 30 days old.
--
-- No foreign key to workspaces(id): this row must outlive the workspace row it describes, since the
-- workspace itself is hard-deleted as the run's final (WORKSPACE_ROOT) phase.
--
-- workspace_id UNIQUE enforces "one active deletion request per workspace" at the database level: a
-- retry after a crash, or a second confirmed request while one is already running, finds the same row
-- via ON CONFLICT (workspace_id) DO NOTHING + SELECT ... FOR UPDATE and returns its current progress
-- instead of starting duplicate work.
CREATE TABLE workspace_deletion_requests (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    rows_deleted BIGINT NOT NULL DEFAULT 0,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_workspace_deletion_requests_status CHECK (status IN ('RUNNING', 'COMPLETED')),
    CONSTRAINT chk_workspace_deletion_requests_phase CHECK (phase IN (
        'ADMISSION', 'REPORTING', 'ATTRIBUTION', 'IDENTITY', 'TRACKING', 'BILLING', 'REVENUE',
        'NOTIFICATION', 'WORKSPACE_ROOT', 'DONE')),
    CONSTRAINT chk_workspace_deletion_requests_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT chk_workspace_deletion_requests_rows CHECK (rows_deleted >= 0)
);

-- Serves the daily tombstone-purge sweep's "completed more than 30 days ago" query.
CREATE INDEX idx_workspace_deletion_requests_completed_at
    ON workspace_deletion_requests (completed_at) WHERE completed_at IS NOT NULL;
