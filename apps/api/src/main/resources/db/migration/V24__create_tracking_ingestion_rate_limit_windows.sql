-- Rate-limits the public ingestion endpoint per ingestion key (#65), closing the gap in
-- ARCHITECTURE.md's security baseline ("Public ingestion is rate-limited..."). DB-backed so the
-- counter stays correct once the API is horizontally scaled -- no correctness-critical state may
-- live only in process memory -- the same reasoning that put StripeBackfillCheckpoint in
-- Postgres instead of memory.
--
-- Fixed-window counter: one row per (ingestion_key_id, window_start), where window_start is the
-- current one-minute window's start instant. IngestionRateLimiter atomically increments the row
-- with INSERT ... ON CONFLICT DO UPDATE ... RETURNING, so concurrent requests against the same
-- key are serialized by Postgres's own row lock and never lose an increment, and every API
-- instance counts against the same row regardless of which one handled the request. Windows older
-- than the current one for a key are opportunistically deleted on each check, so the table never
-- grows unbounded.
CREATE TABLE tracking_ingestion_rate_limit_windows (
    ingestion_key_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (ingestion_key_id, window_start),
    CONSTRAINT fk_rate_limit_windows_key
        FOREIGN KEY (ingestion_key_id) REFERENCES project_ingestion_keys (id) ON DELETE CASCADE,
    CONSTRAINT fk_rate_limit_windows_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_rate_limit_windows_count_positive CHECK (request_count > 0)
);
