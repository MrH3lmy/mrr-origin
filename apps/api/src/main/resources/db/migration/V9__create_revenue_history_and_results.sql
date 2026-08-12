CREATE TABLE revenue_subscription_states (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_customer_id VARCHAR(255) NOT NULL,
    stripe_subscription_id VARCHAR(255) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL,
    source_billing_reference VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_revenue_states_subscription_time
        UNIQUE (workspace_id, stripe_subscription_id, effective_at),
    CONSTRAINT uq_revenue_states_source
        UNIQUE (workspace_id, source_billing_reference),
    CONSTRAINT uq_revenue_states_owner
        UNIQUE (workspace_id, id),
    CONSTRAINT chk_revenue_states_customer
        CHECK (stripe_customer_id <> ''),
    CONSTRAINT chk_revenue_states_subscription
        CHECK (stripe_subscription_id <> ''),
    CONSTRAINT chk_revenue_states_source
        CHECK (source_billing_reference <> ''),
    CONSTRAINT chk_revenue_states_status
        CHECK (status IN (
            'incomplete',
            'incomplete_expired',
            'trialing',
            'active',
            'past_due',
            'canceled',
            'unpaid',
            'paused'
        ))
);

CREATE INDEX idx_revenue_states_customer_time
    ON revenue_subscription_states (workspace_id, stripe_customer_id, effective_at);

CREATE TABLE revenue_subscription_state_items (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    state_id UUID NOT NULL,
    source_item_reference VARCHAR(512) NOT NULL,
    currency VARCHAR(3),
    unit_amount_minor BIGINT,
    quantity NUMERIC(30, 10),
    recurring_interval VARCHAR(16),
    interval_count INTEGER,
    usage_pricing BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_revenue_state_items_source
        UNIQUE (workspace_id, state_id, source_item_reference),
    CONSTRAINT fk_revenue_state_items_state
        FOREIGN KEY (workspace_id, state_id)
        REFERENCES revenue_subscription_states (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_revenue_state_items_source
        CHECK (source_item_reference <> '')
);

CREATE TABLE revenue_subscription_state_discounts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    state_id UUID NOT NULL,
    source_discount_reference VARCHAR(512) NOT NULL,
    source_item_reference VARCHAR(512),
    percent_off NUMERIC(9, 6),
    amount_off_minor BIGINT,
    currency VARCHAR(3),
    start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    CONSTRAINT uq_revenue_state_discounts_source
        UNIQUE (workspace_id, state_id, source_discount_reference),
    CONSTRAINT fk_revenue_state_discounts_state
        FOREIGN KEY (workspace_id, state_id)
        REFERENCES revenue_subscription_states (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_revenue_state_discounts_source
        CHECK (source_discount_reference <> '')
);

CREATE TABLE customer_mrr_snapshots (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_customer_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3),
    amount_minor BIGINT,
    effective_at TIMESTAMPTZ NOT NULL,
    calculation_version VARCHAR(32) NOT NULL,
    supported BOOLEAN NOT NULL,
    unsupported_reason VARCHAR(64),
    source_billing_references TEXT[] NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_mrr_snapshots_result
        UNIQUE NULLS NOT DISTINCT (
            workspace_id,
            stripe_customer_id,
            currency,
            effective_at,
            calculation_version
        ),
    CONSTRAINT chk_customer_mrr_snapshots_currency
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_customer_mrr_snapshots_result
        CHECK (
            (
                supported
                AND amount_minor IS NOT NULL
                AND amount_minor >= 0
                AND unsupported_reason IS NULL
                AND currency IS NOT NULL
            )
            OR (
                NOT supported
                AND amount_minor IS NULL
                AND unsupported_reason IS NOT NULL
                AND currency IS NULL
            )
        )
);

CREATE INDEX idx_customer_mrr_snapshots_tenant
    ON customer_mrr_snapshots (
        workspace_id,
        stripe_customer_id,
        calculation_version,
        effective_at
    );

CREATE TABLE customer_mrr_movements (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    stripe_customer_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    movement_type VARCHAR(16) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    calculation_version VARCHAR(32) NOT NULL,
    source_billing_references TEXT[] NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_mrr_movements_result
        UNIQUE (
            workspace_id,
            stripe_customer_id,
            currency,
            effective_at,
            calculation_version
        ),
    CONSTRAINT chk_customer_mrr_movements_amount
        CHECK (amount_minor > 0),
    CONSTRAINT chk_customer_mrr_movements_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_customer_mrr_movements_type
        CHECK (movement_type IN (
            'NEW',
            'EXPANSION',
            'CONTRACTION',
            'CHURN',
            'REACTIVATION'
        ))
);

CREATE INDEX idx_customer_mrr_movements_tenant
    ON customer_mrr_movements (
        workspace_id,
        stripe_customer_id,
        calculation_version,
        effective_at
    );
