-- Per #64: an append-only audit trail for the manager-only, workspace-wide data export, modeled on
-- V18's export_audit_log but a new table rather than a reuse of it -- export_audit_log's project_id
-- is NOT NULL and its export_type CHECK enumerates only the three project-scoped CSV exports (#26),
-- neither of which fits a workspace-wide, six-domain export. Records that an export happened --
-- actor, workspace, schema version, per-domain row counts, total row count -- and never the exported
-- row content itself, the same "never the exported data" property export_audit_log already holds.
--
-- row_counts is JSONB holding only {"billing": 12, "revenue": 3, ...} -- domain name to row count,
-- never row-level or customer data.
CREATE TABLE workspace_export_audit_log (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    schema_version VARCHAR(32) NOT NULL,
    actor_subject_id VARCHAR(255) NOT NULL,
    row_counts JSONB NOT NULL,
    total_row_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_workspace_export_audit_total_row_count CHECK (total_row_count >= 0)
);

CREATE INDEX idx_workspace_export_audit_workspace
    ON workspace_export_audit_log (workspace_id, created_at, id);
