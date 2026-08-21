# ADR-0011: Include effective customer discounts in subscription MRR recalculation

- Status: Accepted
- Date: 2026-08-21

## Context

Stripe backfill normalizes customers before subscriptions. A customer's legacy singular `discount`
therefore already exists in `billing_discounts` when the subscription phase materializes MRR, but
ADR-0010 initially passed only discounts embedded in the subscription payload. That could emit a
supported, plausible, stale MRR value during the normal backfill path.

## Decision

Before invoking the billing-owned MRR port for an accepted subscription normalization,
`BillingLedgerUpsertService` selects customer-owned, non-deleted discounts within the same workspace
and customer whose interval contains the subscription state's effective time (`start_at <=
effective_at < end_at`, with a null end treated as open for selection). It combines them with the
payload's subscription/item discounts. An identical Stripe discount ID, or identical unscoped
economic terms and effective interval, represents the same discount and is included once.

The derived revenue discount row records whether its origin was customer-level so replay retains the
scope required by ADR-0004. Percentage discounts remain deterministic. Multiple effective discounts
remain `UNSUPPORTED_DISCOUNT`; currency mismatches remain `DISCOUNT_CURRENCY_MISMATCH`; and a fixed
customer discount with multiple paid subscriptions remains
`AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION`. No cross-subscription allocation is invented.

The lookup is scoped by `workspace_id` and `stripe_customer_id` and performed inside the existing
normalization/recalculation transaction. Duplicate, replayed, stale, and out-of-order subscription
events therefore keep ADR-0010's convergence and atomicity guarantees.

## Consequences

- Customer-first Stripe backfills materialize discounted MRR correctly.
- A discount that starts after, or ends at/before, a historical subscription effective time is not
  attached retroactively.
- Revenue exports include the customer-level scope marker so derived results remain auditable.
- Customer discount mutation events still do not independently trigger all-subscription replay; a
  later accepted subscription event consumes the normalized discount state.
