-- Per #20: an append-only audit trail for manual Stripe-customer-link repairs/corrections, layered
-- on top of V8's already-reserved `superseded_at`/`superseded_by_id` supersession contract --
-- StripeCustomerLinkingService#repair is the first writer to actually use that contract, correcting
-- an active link rather than only rejecting a conflicting one (#16's original `link()` behavior,
-- left unchanged).
--
-- One row per successful repair call that actually changed active-link state (a create or a
-- correction); a repeat request that already matches the active link is a no-op and does not grow
-- this log, matching AGENTS.md's idempotency expectations without making the audit trail noisy.
-- Rows are never updated or deleted by application code -- a later repair appends a new row and
-- leaves prior rows exactly as written, so "what changed after the repair" stays reconstructable
-- without re-deriving it from `stripe_customer_links.superseded_by_id` chains.
--
-- `previous_identity_link_id` is the identity's own prior active link, if the target identity was
-- already linked to a different Stripe customer. `previous_customer_link_id` is the target Stripe
-- customer's prior active link, if it was already linked to a different identity. Both are nullable
-- and independent: a pure creation (neither side previously linked) leaves both null and
-- `action_type = 'CREATED'`; any correction sets `action_type = 'CORRECTED'` and at least one of the
-- two previous-link columns.
CREATE TABLE stripe_customer_link_repair_audit_log (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    stripe_customer_id VARCHAR(255) NOT NULL,
    external_user_id VARCHAR(160) NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    new_link_id UUID NOT NULL,
    previous_identity_link_id UUID,
    previous_customer_link_id UUID,
    actor_subject_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_repair_audit_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT fk_repair_audit_new_link
        FOREIGN KEY (new_link_id, project_id, workspace_id)
        REFERENCES stripe_customer_links (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_repair_audit_previous_identity_link
        FOREIGN KEY (previous_identity_link_id, project_id, workspace_id)
        REFERENCES stripe_customer_links (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_repair_audit_previous_customer_link
        FOREIGN KEY (previous_customer_link_id, project_id, workspace_id)
        REFERENCES stripe_customer_links (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT chk_repair_audit_action_type CHECK (action_type IN ('CREATED', 'CORRECTED')),
    CONSTRAINT chk_repair_audit_created_has_no_previous CHECK (
        action_type = 'CORRECTED'
        OR (previous_identity_link_id IS NULL AND previous_customer_link_id IS NULL)
    )
);

CREATE INDEX idx_repair_audit_customer
    ON stripe_customer_link_repair_audit_log (workspace_id, project_id, stripe_customer_id, created_at, id);
