-- Configurable raw-tracking-data retention (#8), scoped like every other project setting. Retention
-- only ever governs the immutable raw envelope layer (tracking_event_envelopes / tracking_ingestion_batches)
-- and tracking_ingestion_failures -- never visitors, sessions, touchpoints, or identity links, which
-- double as acquisition evidence that customer_attribution_results references with ON DELETE RESTRICT
-- (V10) and which ARCHITECTURE.md requires stay available for attribution recalculation. A project
-- with no row here uses TrackingRetentionService.DEFAULT_RETENTION_DAYS, a safe, generous default
-- rather than immediate deletion.
CREATE TABLE project_tracking_retention_settings (
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL PRIMARY KEY,
    retention_days INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_tracking_retention_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_tracking_retention_days_range CHECK (retention_days BETWEEN 1 AND 3650)
);
