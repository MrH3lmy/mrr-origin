-- Stripe recurring prices carry a `recurring.usage_type` of `licensed` or `metered`. A metered price
-- can still have a non-null `unit_amount` (the per-unit rate), so the absence of this column meant
-- MRR recalculation (#83) could not tell a fixed recurring charge from a usage-derived one and would
-- calculate ordinary MRR for a metered price instead of failing visibly as ADR-0004 requires.
ALTER TABLE billing_prices ADD COLUMN usage_type VARCHAR(16);

ALTER TABLE billing_prices ADD CONSTRAINT chk_billing_prices_usage_type
    CHECK (usage_type IS NULL OR usage_type IN ('licensed', 'metered'));
