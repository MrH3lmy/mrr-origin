-- Per #26: an append-only audit trail for CSV exports (comparison/retention-cohorts/customers),
-- modeled directly on V17's `stripe_customer_link_repair_audit_log`. Records that an export
-- happened -- actor, workspace/project, which view, which filters, how many rows -- and never the
-- exported row content itself (no customer identifiers, no monetary totals), so the audit trail
-- carries no exported customer data even though it proves the export occurred.
--
-- `filters` is JSONB holding only query parameters (period, dimension, source/campaign,
-- retention age) -- never row-level or customer data.
--
-- FK to `projects(id, workspace_id) ON DELETE CASCADE` matches V17's own cascade: audit metadata is
-- retained for the lifetime of the workspace and is deleted along with it, without a separate
-- cleanup job.
CREATE TABLE export_audit_log (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    export_type VARCHAR(32) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    actor_subject_id VARCHAR(255) NOT NULL,
    filters JSONB NOT NULL,
    row_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_export_audit_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_export_audit_type
        CHECK (export_type IN ('COMPARISON', 'RETENTION_COHORTS', 'CUSTOMERS')),
    CONSTRAINT chk_export_audit_row_count CHECK (row_count >= 0)
);

CREATE INDEX idx_export_audit_project
    ON export_audit_log (workspace_id, project_id, created_at, id);
