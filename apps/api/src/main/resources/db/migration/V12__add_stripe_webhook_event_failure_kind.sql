-- Per #15: classifies why a stripe_webhook_events row is currently FAILED, so an operator can tell
-- a permanently unsupported Stripe object shape (StripeBillingNormalizationException; replaying
-- without a code change will fail identically) apart from a transient failure (e.g. a Stripe API
-- call error during normalization; a later replay alone may succeed). Reserved bookkeeping only --
-- never touches raw_payload/payload, matching V5's original reserved-columns design for
-- attempt_count/last_error/replay_count/last_replayed_at.
ALTER TABLE stripe_webhook_events
    ADD COLUMN failure_kind VARCHAR(16);

ALTER TABLE stripe_webhook_events
    ADD CONSTRAINT chk_stripe_webhook_events_failure_kind
        CHECK (failure_kind IS NULL OR failure_kind IN ('UNSUPPORTED', 'TRANSIENT'));

-- Mirrors V5's chk_stripe_webhook_events_orphan_consistency pattern: a failure classification is
-- present if and only if the row is currently FAILED. Replaying a FAILED event (StripeWebhookReplayService)
-- resets processing_state to PENDING and clears failure_kind together, in the same statement, so this
-- invariant always holds.
ALTER TABLE stripe_webhook_events
    ADD CONSTRAINT chk_stripe_webhook_events_failure_kind_consistency
        CHECK ((processing_state = 'FAILED') = (failure_kind IS NOT NULL));

-- Supports #15's workspace-scoped health/diagnostics queries (failed/pending/orphaned event counts
-- and lists) without a sequential scan per workspace.
CREATE INDEX idx_stripe_webhook_events_workspace_state ON stripe_webhook_events (workspace_id, processing_state);
