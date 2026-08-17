-- Per #59 (docs/weekly-summary-delivery-plan.md §4): one row per (project, recipient, week) --
-- the idempotency key that prevents duplicate weekly emails regardless of how many scheduler ticks,
-- manual triggers, or retries race. Also doubles as the delivery audit trail (§6): who, which
-- project, which week, how many attempts, final status, and the provider's own message id -- never
-- the rendered subject/body, which is always reproducible on demand from WeeklySummaryService +
-- WeeklySummaryRenderer for the same week_start, so storing it again would duplicate data the same
-- way V17/V18's audit tables already avoid for exported/repaired rows.
--
-- Claiming is fenced by an explicit random lease_token + lease_until, not a timestamp: claim sets
-- status='SENDING', a fresh lease_token, and lease_until in the same statement; apply/fail are
-- fenced by that exact claimed token so a stale worker whose lease already expired can never
-- overwrite a newer outcome in the database, and an expired SENDING row (worker died/restarted
-- mid-attempt) is reclaimable by lease_until <= now. This fences the database apply, not the
-- outbound provider call itself: a merely-paused (not dead) worker can still resume and send after
-- its lease was reclaimed -- an additional, rare at-least-once duplicate source alongside ambiguous
-- network outcomes (see docs/weekly-summary-delivery-plan.md "Delivery guarantee"). last_attempted_at
-- remains informational only. next_attempt_at drives the backoff schedule (§4c) independently of the
-- lease itself. last_outcome_ambiguous accumulates (OR) across attempts rather than reflecting only
-- the most recent one, so an earlier ambiguous attempt is never erased by a later definite outcome.
--
-- recipient_email is nullable: a BLOCKED_MISSING_EMAIL row records an eligible recipient who has no
-- verified email yet (accepted B3 correction) -- an auditable, manager-visible, replayable gap,
-- never a silently-skipped recipient and never a row with no email under any other status.
--
-- CANCELLED (review fix): a claimed row whose recipient turned out, revalidated immediately before
-- send, to no longer be eligible (opted out, or lost/removed manager role) during backoff -- the
-- provider is never called for it. Terminal, not replayable (the recipient's current state is by
-- definition not eligible when this status is set); a future week creates its own fresh row as usual.
CREATE TABLE weekly_summary_deliveries (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    recipient_subject_id VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(320),
    week_start DATE NOT NULL,
    -- 'BLOCKED_MISSING_EMAIL' is 22 characters -- VARCHAR(24) leaves headroom, not a tight fit.
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_outcome_ambiguous BOOLEAN NOT NULL DEFAULT FALSE,
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
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED', 'BLOCKED_MISSING_EMAIL', 'CANCELLED')),
    CONSTRAINT chk_weekly_summary_delivery_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_weekly_summary_delivery_email_presence
        CHECK ((status = 'BLOCKED_MISSING_EMAIL') = (recipient_email IS NULL))
);

CREATE INDEX idx_weekly_summary_delivery_due
    ON weekly_summary_deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_weekly_summary_delivery_reclaim
    ON weekly_summary_deliveries (lease_until)
    WHERE status = 'SENDING';

CREATE INDEX idx_weekly_summary_delivery_workspace
    ON weekly_summary_deliveries (workspace_id, project_id, created_at, id);
