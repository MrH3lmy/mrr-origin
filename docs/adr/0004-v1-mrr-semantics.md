# ADR-0004: V1 MRR normalization and movement semantics

- Status: Accepted
- Date: 2026-08-12

## Context

MRR is recurring-revenue state, not cash. Provider events, invoices, payments, credits,
and refunds are evidence about billing activity, but none is itself an MRR movement.
The same normalized subscription timeline must therefore produce the same result
regardless of webhook delivery order, retries, invoice timing, or payment timing.

V1 needs conservative rules that are reproducible from immutable billing inputs,
auditable in integer minor units, and provider-neutral. Unsupported inputs must remain
visible rather than being converted into plausible-looking revenue.

## Decision

### Unit of calculation

- Calculate subscription MRR separately for each ISO 4217 currency. Store currency and
  integer minor units on every snapshot and movement. Never infer a currency or treat
  major-unit decimals as minor units.
- A recurring item contributes its exact rational period amount: `unit_amount_minor ×
quantity`, after eligible discounts. Quantities must be positive integers; missing,
  fractional, usage-derived, metered, tiered, or transform-quantity values are
  unsupported in V1.
- Monthly prices normalize by dividing the discounted period amount by the interval
  count. Annual prices normalize by dividing it by `12 × interval_count`. Only
  `month` and `year` intervals are supported. Daily, weekly, mixed-currency, and
  unknown intervals fail visibly.
- Sum the exact rational contributions of all supported items in a subscription, then
  round once to integer minor units using half-away-from-zero (`HALF_UP`). MRR cannot
  be negative. This single subscription-boundary rounding rule prevents item-order
  and intermediate-rounding drift.
- Zero-decimal currencies use the same integer-minor-unit rule. Currency exponent is
  presentation metadata and does not change normalization arithmetic.

### Discounts

- Apply an active recurring discount to recurring revenue before interval
  normalization. Percentage discounts use their exact percentage, must be within
  0–100%, and clamp the discounted period amount at zero.
- A fixed-amount discount is supported only when its currency equals the price
  currency and its scope resolves unambiguously to one recurring item. Clamp at zero.
  A subscription-wide fixed discount across multiple recurring items is unsupported
  because V1 has no defensible allocation rule.
- Discount start and end are recurring-state effective dates. A discount starting or
  ending can cause contraction or expansion respectively. Invoice-only credits,
  promotion balances, taxes, and one-time line items do not change MRR.
- Missing discount duration/end data, unsupported discount stacking, or an amount-off
  currency mismatch fails visibly; it is never approximated.

### Subscription states and effective dates

- A trial contributes zero MRR. When the paid recurring state begins, `trial_end` is
  the effective date; trial creation is not New MRR.
- `active` and `past_due` subscriptions retain normalized MRR. A temporary failed
  renewal does not itself change recurring-revenue state while Stripe continues its
  recovery process.
- `incomplete`, `incomplete_expired`, `unpaid`, `canceled`, and `paused`
  subscriptions contribute zero MRR. The transition to one of these zero-MRR states
  uses the provider-declared status-effective timestamp and produces customer-level
  contraction or churn. A later transition back to positive recurring state produces
  expansion or reactivation according to the customer's prior per-currency history.
- Stripe's actual subscription status `paused` contributes zero MRR because invoicing
  has stopped. This is distinct from `pause_collection`: pausing payment collection
  leaves the subscription status and invoicing lifecycle active, so
  `pause_collection` alone does not change MRR. Resume from actual `paused` status
  restores MRR only when the subscription becomes active at a reliable provider time.
- Immediate cancellation churns at the provider's effective cancellation timestamp.
  A scheduled period-end cancellation retains MRR until `current_period_end` and
  churns then. A cancel request or webhook receipt time is not the effective date.
- A full or partial refund, credit note, payment, charge, invoice issue, invoice void,
  or invoice write-off never changes MRR by itself. Such cash events stay separately
  inspectable and may be reported as cash metrics later.
- A recurring price, quantity, interval, supported discount, pause, resume, trial end,
  or cancellation change is effective at the provider-declared state-change time.
  Event creation, ingestion, and processing timestamps are fallback evidence only and
  must not be substituted when the effective time is unknown.
- Equal effective timestamps are grouped before classification. The engine compares
  the complete customer state immediately before the timestamp with the complete state
  immediately after all changes at that timestamp, producing at most one net movement
  per currency. It must not emit offsetting intermediate expansion and contraction.
  Reprocessing or duplicate inputs produces no additional movement.

### Customer movements

Compare a customer's total supported subscription MRR immediately before and after an
effective timestamp, independently per currency:

| Before |                                       After | Classification |
| -----: | ------------------------------------------: | -------------- |
|    `0` |  `> 0`, customer has never had positive MRR | New            |
|    `0` | `> 0`, customer previously had positive MRR | Reactivation   |
|  `> 0` |                                     greater | Expansion      |
|  `> 0` |                             lower but `> 0` | Contraction    |
|  `> 0` |                                         `0` | Churn          |
|  equal |                                       equal | No movement    |

Movement amount is the absolute integer-minor-unit difference. Customer aggregation
means canceling one of several subscriptions is contraction unless total customer MRR
reaches zero. Movement history is tracked independently per currency: moving directly
between currencies is a churn in the old currency and New in a currency where that
customer has never had positive MRR, or Reactivation where that customer previously
had positive MRR in that currency. Currency amounts are never netted or converted.

### Retained MRR and NRR

- Retained MRR is the sum, per currency, of a fixed acquisition cohort's customer MRR
  at the selected cohort age. It includes expansion and reactivation that is active at
  that age and is zero for churned customers. Cohort membership never changes after
  acquisition.
- NRR is calculated per currency and fixed customer cohort for a half-open window
  `[start, end)`: `(starting MRR + expansion - contraction - churn) / starting MRR`.
  New and reactivation movements are excluded. Retained MRR at `end` is an equivalent
  cross-check when no movement occurs exactly at the excluded endpoint.
- NRR is undefined when starting MRR is zero. Cross-currency totals and NRR are
  unsupported in V1 because no reporting-currency/FX policy is approved.

### Visible failure contract

Normalization returns either a complete supported result or a structured unsupported
result containing a stable reason code and references to the immutable source objects.
It must not emit a partial snapshot, partial movement, zero substitute, guessed date,
guessed quantity, guessed currency, or synthetic exchange rate. Supported and
unsupported golden cases in `apps/api/src/test/resources/golden/mrr-v1.json` are the
executable contract for issue #14; this ADR does not implement that engine.

Initial V1 reason codes are:

- `UNKNOWN_EFFECTIVE_AT`
- `UNKNOWN_CURRENCY`
- `UNSUPPORTED_INTERVAL`
- `UNSUPPORTED_QUANTITY`
- `UNSUPPORTED_USAGE_PRICING`
- `UNSUPPORTED_DISCOUNT`
- `DISCOUNT_CURRENCY_MISMATCH`
- `AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION`
- `MIXED_CURRENCY_SUBSCRIPTION`
- `CROSS_CURRENCY_AGGREGATION`
- `ZERO_STARTING_MRR_FOR_NRR`

## Consequences

- Results are deterministic, replayable, and independent of cash collection.
- Annual normalization and percentage discounts can create fractional minor units;
  the documented subscription-boundary rounding makes those results stable.
- `past_due` MRR may remain positive during recovery even when cash has not been
  collected; `unpaid` is zero because Stripe's recovery lifecycle has ended. The UI
  must present billing health separately rather than silently redefining MRR.
- V1 reports separate currency series and cannot show a truthful global MRR/NRR total.
  A reporting-currency source and historical FX policy require a later ADR.
- Metered/tiered prices and ambiguous discounts surface in data health until a later
  decision adds a complete normalization rule.
