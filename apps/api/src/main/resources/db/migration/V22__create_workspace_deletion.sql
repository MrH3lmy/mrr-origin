-- Per #62 (accepted contract on #27): owner-only, resumable, cross-module hard deletion of an
-- entire workspace. Generalizes ProjectDataDeletionService's (#8, V16) fetch-a-bounded-slice /
-- apply / advance-checkpoint pattern from one project's tracking data to every workspace-owned
-- table across billing, revenue, attribution, reporting, notification, and tracking.
--
-- `status` on workspaces gates new writes while a deletion is in progress (WorkspaceContext#requireManager
-- and #requireOwner reject with 409 once a workspace is DELETING); it never gates reads, so a
-- founder can still see their workspace is being deleted.
ALTER TABLE workspaces ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE workspaces ADD CONSTRAINT chk_workspaces_status CHECK (status IN ('ACTIVE', 'DELETING'));

-- One lifetime row per workspace while a deletion is in progress. Deliberately has no unique
-- constraint beyond workspace_id (a workspace can only ever have one deletion in flight) and cascades
-- away automatically in the same transaction that deletes the workspaces row itself (the run's final
-- WORKSPACE phase) -- workspace_deletion_tombstones below is the durable post-deletion record, not
-- this table.
CREATE TABLE workspace_data_deletion_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL UNIQUE REFERENCES workspaces (id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    phase VARCHAR(40) NOT NULL,
    rows_deleted BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_workspace_deletion_run_status CHECK (status IN ('RUNNING', 'COMPLETED')),
    -- No phase for project_tracking_retention_settings or weekly_summary_opt_outs: neither has a
    -- single-column `id` primary key (project_id, and (project_id, subject_id), respectively), both
    -- are ON DELETE CASCADE from projects/workspace_members, and both are small per-project settings
    -- tables rather than growth data -- they are left to cascade away in the WORKSPACE phase's final
    -- `DELETE FROM workspaces` instead of needing their own bounded-batch phase.
    CONSTRAINT chk_workspace_deletion_run_phase CHECK (phase IN (
        'REVOKE_INGESTION_KEYS', 'DISABLE_STRIPE_SYNC',
        'EXPORT_AUDIT_LOG', 'WEEKLY_SUMMARY_DELIVERIES', 'STRIPE_CUSTOMER_LINK_REPAIR_AUDIT_LOG',
        'TRACKING_INGESTION_FAILURES', 'TRACKING_VERIFICATION_ATTEMPTS',
        'ATTRIBUTION_RESULTS', 'ATTRIBUTION_RECALCULATION_RUNS',
        'TRACKING_EVENT_ENVELOPES', 'TRACKING_INGESTION_BATCHES',
        'TOUCHPOINTS', 'TRACKING_SESSIONS', 'VISITOR_ALIASES', 'VISITORS',
        'STRIPE_CUSTOMER_LINKS', 'EXTERNAL_IDENTITIES',
        'PROJECT_INGESTION_KEYS', 'PROJECT_ALLOWED_DOMAINS',
        'REVENUE_SUBSCRIPTION_STATE_ITEMS', 'REVENUE_SUBSCRIPTION_STATE_DISCOUNTS', 'REVENUE_SUBSCRIPTION_STATES',
        'CUSTOMER_MRR_MOVEMENTS', 'CUSTOMER_MRR_SNAPSHOTS',
        'BILLING_SUBSCRIPTION_STATUS_EVENTS', 'BILLING_SUBSCRIPTION_ITEMS', 'BILLING_SUBSCRIPTIONS',
        'BILLING_INVOICES', 'BILLING_PAYMENTS', 'BILLING_REFUNDS', 'BILLING_DISCOUNTS', 'BILLING_PRICES',
        'BILLING_CUSTOMERS',
        'STRIPE_WEBHOOK_EVENTS', 'STRIPE_OAUTH_STATES', 'STRIPE_CONNECTIONS',
        'WORKSPACE', 'DONE')),
    CONSTRAINT chk_workspace_deletion_run_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT chk_workspace_deletion_run_rows CHECK (rows_deleted >= 0)
);

-- Durable, minimal, non-PII record that a workspace was deleted -- written in the same transaction
-- as the workspaces row's final delete, so it survives after workspace_data_deletion_runs cascades
-- away. No FK to workspaces (the row it would reference is gone by the time this is written), and
-- deliberately carries nothing beyond what the accepted #27 contract calls for: request ID,
-- workspace UUID, status, timestamps. No customer data, no billing data, no member subject IDs.
-- Purged after 30 days by WorkspaceDeletionTombstonePurgeJob.
CREATE TABLE workspace_deletion_tombstones (
    request_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_deletion_tombstone_status CHECK (status IN ('COMPLETED'))
);

CREATE INDEX idx_workspace_deletion_tombstones_created_at ON workspace_deletion_tombstones (created_at);
