-- Bounded, project-scoped record of public-ingestion rejections (#8), so a founder can see *why*
-- traffic looks missing without ever exposing the rejected request's secret or payload. Three kinds
-- are recorded, matching EventIngestionController's own rejection points:
--   BLOCKED_ORIGIN  -- a resolvable ingestion key, but Origin was missing/malformed/not allow-listed.
--   INVALID_KEY     -- a key whose public, non-secret prefix (project_ingestion_keys.key_prefix)
--                      matches an issued key for a real project, but whose full value did not
--                      resolve (wrong secret, or a revoked key). A key whose prefix matches no
--                      project at all cannot be attributed to any tenant and is never recorded here.
--   INVALID_PAYLOAD -- a resolvable ingestion key, but the request body failed envelope validation.
-- No column ever stores the raw ingestion key, its hash, the request body, or any event payload --
-- `detail` is limited to a normalized origin host (BLOCKED_ORIGIN) or a fixed validation-failure
-- code (INVALID_PAYLOAD); it is always NULL for INVALID_KEY.
CREATE TABLE tracking_ingestion_failures (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    detail VARCHAR(253),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ingestion_failures_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_ingestion_failures_kind CHECK (kind IN ('BLOCKED_ORIGIN', 'INVALID_KEY', 'INVALID_PAYLOAD'))
);

-- Serves both the diagnostics report's "most recent + count per kind" query and
-- TrackingIngestionFailureRecorder's own bounded-retention trim (keep only the most recent N rows
-- per project/kind), which is what keeps this table's growth bounded even though public ingestion
-- has no rate limiting yet.
CREATE INDEX idx_ingestion_failures_project_kind_occurred
    ON tracking_ingestion_failures (workspace_id, project_id, kind, occurred_at DESC);
