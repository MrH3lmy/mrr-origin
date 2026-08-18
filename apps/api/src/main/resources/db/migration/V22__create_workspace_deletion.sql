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

-- The in-flight run's durable checkpoint -- one lifetime row per workspace while a deletion is
-- RUNNING. `phase` matches WorkspaceDeletionPhase exactly; `rows_deleted` accumulates the bulk-delete
-- row count across every phase. The row is read with SELECT ... FOR UPDATE before each batch, exactly
-- like project_data_deletion_runs -- that row lock IS this run's multi-instance-safe lease.
--
-- Foreign-keyed to workspaces with ON DELETE CASCADE and deliberately holds nothing this table's own
-- purpose doesn't need: it cascades away automatically in the same statement that deletes the
-- workspaces row (the run's final WORKSPACE_ROOT phase), which is also the same transaction that
-- inserts the row's replacement, workspace_deletion_tombstones below. There is no window where both
-- rows exist, and no window where neither does.
CREATE TABLE workspace_deletion_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL UNIQUE REFERENCES workspaces (id) ON DELETE CASCADE,
    phase VARCHAR(24) NOT NULL,
    rows_deleted BIGINT NOT NULL DEFAULT 0,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_deletion_runs_phase CHECK (phase IN (
        'ADMISSION', 'NOTIFICATION', 'REPORTING', 'ATTRIBUTION', 'REVENUE', 'IDENTITY', 'TRACKING',
        'BILLING', 'WORKSPACE_ROOT', 'DONE')),
    CONSTRAINT chk_workspace_deletion_runs_rows CHECK (rows_deleted >= 0)
);

-- The durable, minimal, non-PII record that a workspace was deleted -- written in the same
-- transaction as the workspaces row's final delete (which cascades workspace_deletion_runs away), so
-- this is the only surviving trace once a deletion completes. Deliberately has no foreign key to
-- workspaces (the row it would reference is gone by the time this is written) and no columns beyond
-- exactly what the accepted contract calls for: request ID, workspace UUID, status, and timestamps.
-- No requester subject ID, no customer data, no billing data -- this table cannot leak PII because it
-- was never given a column capable of holding any. Purged after 30 days by
-- WorkspaceTombstonePurgeService.
CREATE TABLE workspace_deletion_tombstones (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_deletion_tombstones_status CHECK (status = 'COMPLETED')
);

-- Serves the daily tombstone-purge sweep's "completed more than 30 days ago" query.
CREATE INDEX idx_workspace_deletion_tombstones_completed_at
    ON workspace_deletion_tombstones (completed_at);
