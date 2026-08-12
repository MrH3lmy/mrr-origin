-- Per #16: an explicit server-side identity bridge between a project's tracked external-user
-- identity (V6 external_identities, created by identify()) and a workspace's Stripe customer
-- (V7 billing_customers, created by backfill/webhook normalization). This is deliberately narrower
-- than a general repair/attribution surface -- see ARCHITECTURE.md's identity module and evidence
-- table, and Phase 4's "Stripe customer to application-user identity bridge" outcome.
--
-- Structural tenant isolation, not just convention: both foreign keys are composite so a row
-- claiming one workspace/project can never structurally reference another tenant's identity or
-- customer, even if the caller guesses a real ID that belongs to someone else.
--
-- Evidence: `evidence_source` is constrained to the two sources ARCHITECTURE.md's evidence table
-- names for this bridge. Only EXPLICIT_API is written by #16 -- the explicit, workspace-manager-
-- authenticated linking call, proven by the FK to an actually-observed billing_customers row.
-- STRIPE_METADATA is reserved for a follow-up issue: billing_customers does not yet persist Stripe
-- `metadata`, so there is no deterministic, inspectable evidence available today to drive it.
--
-- Supersession: `superseded_at`/`superseded_by_id` are reserved, append-only-friendly columns for a
-- future repair-workflow issue to correct a link without deleting its history (per ADR-0002). Both
-- values must be null or non-null together, self-supersession is rejected, and the deferred
-- composite foreign key requires the replacement link to belong to the same workspace and project.
-- #16 itself never marks a row superseded -- a conflicting active link is rejected outright.
CREATE TABLE stripe_customer_links (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    external_identity_id UUID NOT NULL,
    stripe_customer_id VARCHAR(255) NOT NULL,
    evidence_source VARCHAR(24) NOT NULL,
    evidence_reference VARCHAR(512) NOT NULL,
    linked_by_subject_id VARCHAR(255) NOT NULL,
    superseded_at TIMESTAMPTZ,
    superseded_by_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stripe_customer_links_owner
        UNIQUE (id, project_id, workspace_id),
    CONSTRAINT chk_stripe_customer_links_supersession_pair
        CHECK ((superseded_at IS NULL) = (superseded_by_id IS NULL)),
    CONSTRAINT chk_stripe_customer_links_not_self_superseded
        CHECK (superseded_by_id IS NULL OR superseded_by_id <> id),
    CONSTRAINT fk_stripe_customer_links_identity
        FOREIGN KEY (external_identity_id, project_id, workspace_id)
        REFERENCES external_identities (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stripe_customer_links_customer
        FOREIGN KEY (workspace_id, stripe_customer_id)
        REFERENCES billing_customers (workspace_id, stripe_customer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stripe_customer_links_superseded_by
        FOREIGN KEY (superseded_by_id, project_id, workspace_id)
        REFERENCES stripe_customer_links (id, project_id, workspace_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_stripe_customer_links_evidence_source
        CHECK (evidence_source IN ('EXPLICIT_API', 'STRIPE_METADATA'))
);

-- At most one ACTIVE (non-superseded) link per tracked external identity, and at most one ACTIVE
-- link per workspace's Stripe customer -- enforced by the database itself, not merely application
-- code, so a conflicting link attempt fails even under concurrent requests.
CREATE UNIQUE INDEX uq_stripe_customer_links_active_identity
    ON stripe_customer_links (external_identity_id) WHERE superseded_at IS NULL;
CREATE UNIQUE INDEX uq_stripe_customer_links_active_customer
    ON stripe_customer_links (workspace_id, stripe_customer_id) WHERE superseded_at IS NULL;

CREATE INDEX idx_stripe_customer_links_workspace_project
    ON stripe_customer_links (workspace_id, project_id);
