-- Per #12 and ARCHITECTURE.md's reliability rules: normalized, tenant-owned billing state built
-- from two convergent sources -- the resumable Stripe backfill and the immutable V5 webhook queue
-- -- retaining provider IDs so both paths can be replayed and reconciled.
--
-- Cross-object references between these tables (e.g. a subscription's customer, an invoice's
-- subscription) are stored as plain Stripe ID columns, not foreign keys, by design: Stripe does
-- not guarantee that related objects are delivered or backfilled in dependency order, and forcing
-- referential integrity across object types would require synthesizing placeholder rows just to
-- satisfy a constraint. Within a single object's own aggregate (a subscription and its items,
-- always written together in one upsert), a real foreign key is used.
--
-- Convergence: every row carries an ordering PAIR, compared as a Postgres row value --
-- `(source_version, source_sequence) <= (EXCLUDED.source_version, EXCLUDED.source_sequence)` --
-- so ties on the first component always fall back to a second, independently guaranteed-unique
-- component rather than silently colliding.
--
-- `source_version` is the coarse, human-meaningful ordering: the Stripe event's `created`
-- epoch-second (webhook) or the wall-clock second the backfill page request was issued (backfill),
-- each shifted left by a factor of 2,000,000 and combined with a sub-second component in the
-- low-order digits (a webhook's own receipt-time microseconds, always < 1,000,000; or a fixed
-- backfill sentinel of 1,000,000, so a backfill snapshot always wins a same-second tie against a
-- webhook event -- it reflects Stripe's true live state as of a strictly later instant).
--
-- `source_sequence` is the tie-breaker of last resort, for the case `source_version` alone cannot
-- distinguish: two webhook events with identical `created` AND identical receipt-time microsecond
-- (adversarial or coincidental, but not impossible). For webhook-origin rows this is
-- `stripe_webhook_events.ingest_sequence` -- a `GENERATED ALWAYS AS IDENTITY` column, so strictly
-- increasing and unique per row by construction, assigned once at insert and never touched again
-- (immutable, independent of whatever order the normalization worker later processes PENDING rows
-- in, so replaying the same stored events in any order still converges identically). Backfill-origin
-- rows use a constant 0: two backfill fetches of the same object always carry identical content
-- (both are "the current live state"), so their relative order never needs to be distinguished.
--
-- See BillingSourceVersion. Replays, retries, restarts, and interleaved backfill/webhook delivery
-- for the same object always converge on the most recent known state regardless of arrival or
-- processing order, and a same-(version,sequence) replay is a harmless no-op rewrite.
ALTER TABLE stripe_webhook_events ADD COLUMN ingest_sequence BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE TABLE billing_customers (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_customer_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    provider_created_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_customers_workspace_stripe_id UNIQUE (workspace_id, stripe_customer_id),
    CONSTRAINT chk_billing_customers_source CHECK (source IN ('BACKFILL', 'WEBHOOK'))
);

CREATE INDEX idx_billing_customers_workspace ON billing_customers (workspace_id);

CREATE TABLE billing_prices (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_price_id VARCHAR(255) NOT NULL,
    stripe_product_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    unit_amount BIGINT,
    billing_scheme VARCHAR(32) NOT NULL,
    type VARCHAR(16) NOT NULL,
    recurring_interval VARCHAR(16),
    recurring_interval_count INTEGER,
    active BOOLEAN NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_prices_workspace_stripe_id UNIQUE (workspace_id, stripe_price_id),
    CONSTRAINT chk_billing_prices_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT chk_billing_prices_type CHECK (type IN ('one_time', 'recurring')),
    CONSTRAINT chk_billing_prices_recurring_interval
        CHECK ((type = 'recurring') = (recurring_interval IS NOT NULL AND recurring_interval_count IS NOT NULL))
);

CREATE INDEX idx_billing_prices_workspace ON billing_prices (workspace_id);

CREATE TABLE billing_subscriptions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_subscription_id VARCHAR(255) NOT NULL,
    stripe_customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    trial_start TIMESTAMPTZ,
    trial_end TIMESTAMPTZ,
    collection_method VARCHAR(32),
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_subscriptions_workspace_stripe_id UNIQUE (workspace_id, stripe_subscription_id),
    -- Required so billing_subscription_items/billing_subscription_status_events can enforce a
    -- composite (workspace_id, subscription_id) foreign key below -- structurally impossible for a
    -- row to reference a subscription belonging to a different workspace, not merely convention.
    CONSTRAINT uq_billing_subscriptions_workspace_id UNIQUE (workspace_id, id),
    CONSTRAINT chk_billing_subscriptions_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT chk_billing_subscriptions_status CHECK (status IN
        ('incomplete', 'incomplete_expired', 'trialing', 'active', 'past_due', 'canceled', 'unpaid', 'paused'))
);

CREATE INDEX idx_billing_subscriptions_workspace ON billing_subscriptions (workspace_id);
CREATE INDEX idx_billing_subscriptions_customer ON billing_subscriptions (workspace_id, stripe_customer_id);

CREATE TABLE billing_subscription_items (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    stripe_subscription_item_id VARCHAR(255) NOT NULL,
    stripe_price_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_subscription_items_workspace_stripe_id UNIQUE (workspace_id, stripe_subscription_item_id),
    CONSTRAINT chk_billing_subscription_items_quantity CHECK (quantity >= 0),
    -- Composite FK: a row's workspace_id must match its own subscription's workspace_id, so a
    -- workspace-A item can never structurally reference a workspace-B subscription.
    CONSTRAINT fk_billing_subscription_items_subscription
        FOREIGN KEY (workspace_id, subscription_id) REFERENCES billing_subscriptions (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_billing_subscription_items_subscription ON billing_subscription_items (subscription_id);

-- One row per applied state transition that actually changed a subscription's status, keyed so a
-- replay of the same source_version (duplicate webhook delivery, or a reprocessed backfill page)
-- can never insert a second transition row for the same underlying change.
CREATE TABLE billing_subscription_status_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    stripe_subscription_id VARCHAR(255) NOT NULL,
    previous_status VARCHAR(24),
    new_status VARCHAR(24) NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_subscription_status_events_version
        UNIQUE (workspace_id, stripe_subscription_id, source_version, source_sequence),
    CONSTRAINT chk_billing_subscription_status_events_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT fk_billing_subscription_status_events_subscription
        FOREIGN KEY (workspace_id, subscription_id) REFERENCES billing_subscriptions (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_billing_subscription_status_events_subscription
    ON billing_subscription_status_events (subscription_id);

CREATE TABLE billing_invoices (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_invoice_id VARCHAR(255) NOT NULL,
    stripe_customer_id VARCHAR(255) NOT NULL,
    stripe_subscription_id VARCHAR(255),
    status VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_due BIGINT NOT NULL,
    amount_paid BIGINT NOT NULL,
    amount_remaining BIGINT NOT NULL,
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    provider_created_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_invoices_workspace_stripe_id UNIQUE (workspace_id, stripe_invoice_id),
    CONSTRAINT chk_billing_invoices_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT chk_billing_invoices_status CHECK (status IN ('draft', 'open', 'paid', 'uncollectible', 'void')),
    CONSTRAINT chk_billing_invoices_amounts CHECK (amount_due >= 0 AND amount_paid >= 0 AND amount_remaining >= 0)
);

CREATE INDEX idx_billing_invoices_workspace ON billing_invoices (workspace_id);
CREATE INDEX idx_billing_invoices_customer ON billing_invoices (workspace_id, stripe_customer_id);
CREATE INDEX idx_billing_invoices_subscription ON billing_invoices (workspace_id, stripe_subscription_id);

-- Normalized from Stripe Charge objects: the payment-side cash event, as distinct from the
-- invoice's recurring-revenue state.
CREATE TABLE billing_payments (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_charge_id VARCHAR(255) NOT NULL,
    stripe_customer_id VARCHAR(255),
    stripe_invoice_id VARCHAR(255),
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    paid BOOLEAN NOT NULL,
    refunded BOOLEAN NOT NULL DEFAULT FALSE,
    amount_refunded BIGINT NOT NULL DEFAULT 0,
    provider_created_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_payments_workspace_stripe_id UNIQUE (workspace_id, stripe_charge_id),
    CONSTRAINT chk_billing_payments_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT chk_billing_payments_status CHECK (status IN ('succeeded', 'pending', 'failed')),
    CONSTRAINT chk_billing_payments_amounts CHECK (amount >= 0 AND amount_refunded >= 0)
);

CREATE INDEX idx_billing_payments_workspace ON billing_payments (workspace_id);
CREATE INDEX idx_billing_payments_invoice ON billing_payments (workspace_id, stripe_invoice_id);

CREATE TABLE billing_refunds (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_refund_id VARCHAR(255) NOT NULL,
    stripe_charge_id VARCHAR(255) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(32),
    provider_created_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_refunds_workspace_stripe_id UNIQUE (workspace_id, stripe_refund_id),
    CONSTRAINT chk_billing_refunds_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT chk_billing_refunds_status
        CHECK (status IN ('pending', 'succeeded', 'failed', 'canceled', 'requires_action')),
    CONSTRAINT chk_billing_refunds_amount CHECK (amount >= 0)
);

CREATE INDEX idx_billing_refunds_workspace ON billing_refunds (workspace_id);
CREATE INDEX idx_billing_refunds_charge ON billing_refunds (workspace_id, stripe_charge_id);

-- Per https://docs.stripe.com/api/subscriptions/object, subscriptions (and subscription items)
-- support *multiple* compound discounts (the `discounts` array), unlike the single `discount`
-- field on a Customer -- this table already supports many rows per owner, one per stripe_discount_id.
CREATE TABLE billing_discounts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_discount_id VARCHAR(255) NOT NULL,
    stripe_customer_id VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    stripe_subscription_item_id VARCHAR(255),
    stripe_coupon_id VARCHAR(255) NOT NULL,
    percent_off NUMERIC(6, 3),
    amount_off BIGINT,
    currency VARCHAR(3),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    source VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_discounts_workspace_stripe_id UNIQUE (workspace_id, stripe_discount_id),
    CONSTRAINT chk_billing_discounts_source CHECK (source IN ('BACKFILL', 'WEBHOOK')),
    CONSTRAINT chk_billing_discounts_owner CHECK (
        stripe_customer_id IS NOT NULL OR stripe_subscription_id IS NOT NULL OR stripe_subscription_item_id IS NOT NULL)
);

CREATE INDEX idx_billing_discounts_workspace ON billing_discounts (workspace_id);
CREATE INDEX idx_billing_discounts_customer ON billing_discounts (workspace_id, stripe_customer_id);
CREATE INDEX idx_billing_discounts_subscription ON billing_discounts (workspace_id, stripe_subscription_id);
CREATE INDEX idx_billing_discounts_subscription_item ON billing_discounts (workspace_id, stripe_subscription_item_id);
