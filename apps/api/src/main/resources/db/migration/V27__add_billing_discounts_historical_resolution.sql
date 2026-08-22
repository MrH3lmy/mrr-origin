-- Per ADR-0011's historical-state amendment (#86 follow-up): billing_discounts is a single
-- upserted current-state row per stripe_discount_id (like every other billing_* ledger table), so
-- a later delete or a genuine coupon-switch update in place can erase the evidence an *older*
-- customer.subscription.* recalculation needs to resolve what was actually effective at its own,
-- earlier effective_at. Two independent gaps, two independent columns:
--
-- 1. A customer.discount.deleted event with no Stripe-provided `end` previously left end_at NULL
--    even though the row was marked deleted -- silently reopening the discount's window to
--    "forever" for any historical (or even current) temporal query that stops trusting the
--    `deleted` flag. first_seen_start_at doesn't fix this; the BillingLedgerUpsertService.java
--    change in this commit does (persisting the same provider-event-second fallback already used
--    for effective_at into end_at itself).
--
-- 2. first_seen_start_at records the EARLIEST start_at this discount identity has ever carried,
--    set once at first insert and never touched by any later UPDATE (simply omitted from the
--    upsert's SET list). start_at itself keeps moving forward on a genuine customer.discount.updated
--    coupon switch (Stripe's own semantics: "occurs whenever a customer is switched from one coupon
--    to another"), but the OLD coupon's terms are gone the moment the row is overwritten -- this
--    table has never stored history. The gap between first_seen_start_at and the current start_at is
--    exactly the window billing_discounts can no longer answer for; BillingLedgerUpsertService
--    surfaces that window as an explicit, replayable failure instead of guessing.
ALTER TABLE billing_discounts
    ADD COLUMN first_seen_start_at TIMESTAMPTZ;

UPDATE billing_discounts SET first_seen_start_at = start_at WHERE first_seen_start_at IS NULL;

ALTER TABLE billing_discounts
    ALTER COLUMN first_seen_start_at SET NOT NULL;
