ALTER TABLE revenue_subscription_state_discounts
    ADD COLUMN customer_level BOOLEAN NOT NULL DEFAULT FALSE;
