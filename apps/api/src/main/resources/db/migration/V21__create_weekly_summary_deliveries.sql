-- Per #59 (docs/weekly-summary-delivery-plan.md §4): one row per (project, recipient, week) --
-- the idempotency key that prevents duplicate weekly emails regardless of how many scheduler ticks,
-- manual triggers, or retries race. Also doubles as the delivery audit trail (§6): who, which
-- project, which week, how many attempts, final status, and the provider's own message id -- never
-- the rendered subject/body, which is always reproducible on demand from WeeklySummaryService +
-- WeeklySummaryRenderer for the same week_start, so storing it again would duplicate data the same
-- way V17/V18's audit tables already avoid for exported/repaired rows.
--
-- last_attempted_at is the lease stamp, fenced exactly like stripe_webhook_events (V5): claim sets
-- status='SENDING' and last_attempted_at in the same statement; apply/fail are fenced by that exact
-- claimed value so a stale worker whose lease already expired can never overwrite a newer outcome.
-- next_attempt_at drives the backoff schedule (§4c) independently of the lease itself.
CREATE TABLE weekly_summary_deliveries (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    recipient_subject_id VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    week_start DATE NOT NULL,
    -- 'PERMANENTLY_FAILED' is 19 characters -- VARCHAR(24) leaves headroom, not a tight fit.
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    provider_message_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_weekly_summary_delivery_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_weekly_summary_delivery UNIQUE (project_id, recipient_subject_id, week_start),
    CONSTRAINT chk_weekly_summary_delivery_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED')),
    CONSTRAINT chk_weekly_summary_delivery_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_weekly_summary_delivery_due
    ON weekly_summary_deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_weekly_summary_delivery_workspace
    ON weekly_summary_deliveries (workspace_id, project_id, created_at, id);
