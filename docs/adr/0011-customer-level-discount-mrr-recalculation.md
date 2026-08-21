# ADR-0011: Legacy customer-level discount MRR recalculation (#86)

- Status: Accepted
- Date: 2026-08-21

## Context

ADR-0010 wired `customer.subscription.*` normalization into deterministic MRR recalculation, but
documented one known limitation: legacy top-level `customer.discount.created/updated/deleted` events
(a customer's singular `discount` field, distinct from a subscription's compound `discounts` array)
were normalized into `billing_discounts` without ever triggering recalculation. A workspace relying
on that legacy field would see billing state update while MRR silently stayed stale -- a violation of
this repository's core invariant that a successfully processed Stripe event never leaves a plausible
but stale MRR value.

Closing that gap requires deciding what happens when a discount that is not scoped to any one
subscription needs to affect a customer who may have zero, one, or several subscriptions, possibly in
different currencies. ADR-0004 already decided the adjacent, narrower question -- a fixed-amount
discount spanning multiple recurring items within one subscription is unsupported because "V1 has no
defensible allocation rule" -- but says nothing about a discount scoped above the subscription level.

## Decision

`CustomerDiscountMrrRecalculationService` (new, `billing` module) intercepts customer-scoped discount
events after `BillingLedgerUpsertService.upsertDiscount` applies (extended to report whether the write
was stale, mirroring `upsertSubscription`'s existing pattern) and recalculates every affected
subscription synchronously, in the same transaction, through the same `BillingMrrRecalculationPort`
the subscription path already uses. This _is_ new orchestration in `billing`, not a new calculation
rule -- but it required one narrow addition to `RevenueCalculationService` itself: a `customer_level`
flag threaded through `RevenueModels`, `BillingMrrRecalculationPort`, `BillingMrrRecalculationAdapter`,
and persisted on `revenue_subscription_state_discounts` (migration V26), so that
`RevenueCalculationService.calculate` can tell a customer-scoped discount apart from a
subscription/item-scoped one when a customer has more than one paid (`active`/`past_due`)
subscription. That distinction feeds exactly one new guard -- `AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION`,
thrown when a fixed-amount customer-level discount is active across more than one paid subscription --
mirroring the fixed-amount rejection described below at the orchestration layer, at the calculation
layer where replay (`history`/`calculate`) also needs to see it. No other calculation semantics
changed: percentage math, per-subscription discount-count guards, currency checks, and ADR-0004's
stacking rule are all unchanged.

"Affected" is a customer's subscriptions currently in `active`/`past_due` status -- ADR-0004's only
MRR-retaining statuses. A churned or trialing subscription contributes zero MRR regardless of discount
state, so it is excluded both from recalculation (a needless no-op) and from the ambiguity count below
(it was never really in scope).

### Percentage discounts: fan out, always supported when any subscription is affected

A percentage composes independently per subscription -- there is no allocation choice to make. Fanning
the same `percent_off` out to every affected subscription is not a new rule; it is the identical
per-subscription math ADR-0004 already approves for one subscription-wide discount, run once per
affected subscription instead of once. This holds regardless of how many subscriptions or currencies
are involved, since percentage discounts carry no currency.

### Fixed-amount discounts: supported only when exactly one subscription is affected

When a customer has more than one `active`/`past_due` subscription, a fixed-amount discount cannot be
deterministically allocated: split it, apply it in full to each, or apply it to only one? None of these
is written down anywhere in this codebase's approved semantics, and inventing one would fabricate
revenue exactly as ADR-0004 already refuses to do at the item level. `CustomerDiscountMrrRecalculationService`
throws `StripeBillingNormalizationException` in that case, which -- via `StripeWebhookNormalizationService`'s
existing failure handling -- rolls back the whole transaction (the `billing_discounts` write included)
and marks the event `FAILED`/`UNSUPPORTED`: inspectable and replayable, never silently accepted.

When exactly one subscription is affected, the existing engine's own guards (`AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION`
for a multi-item subscription, `DISCOUNT_CURRENCY_MISMATCH` for a currency mismatch) apply completely
unchanged, because the discount is fed into that one subscription's state exactly as a subscription-wide
fixed discount already is.

### Existing per-subscription discounts: reuse the stacking guard, add no new one

Each affected subscription's current subscription/item-level discounts (from `billing_discounts`,
already normalized by prior `customer.subscription.*` events) are included alongside the new
customer-level one. `RevenueCalculationService`'s pre-existing "at most one active discount per
subscription state" check then fails a stacked combination visibly (`UNSUPPORTED_DISCOUNT`), the same
way it already does for two subscription-embedded discounts. No new stacking-detection logic exists in
`CustomerDiscountMrrRecalculationService`.

### Delete

The discount is simply omitted from the reconstructed per-subscription discount list from its
effective end onward. ADR-0004's existing customer-movement classification table produces the correct
expansion/reactivation on its own; no new movement logic was needed. `end_at` is persisted with the
same value used as `effective_at` below (Stripe's own `end`, or the provider-event-second fallback),
never left `NULL` on a deleted row -- see "Historical state" below for why that persistence matters
beyond this event's own recalculation.

### `effective_at`

Create/update use the discount's own provider-declared `start` (always present; required by the
parser). Delete uses `end` when Stripe provided one, else the event's own provider-declared second --
exactly ADR-0010's documented last-resort fallback for a transition with no more specific field,
extended to this event type rather than a new rule. No fabricated timestamp is ever introduced.

### Idempotency and ordering

`source_billing_reference` is `{source}:{version}:{sequence}:{stripeSubscriptionId}` -- the identical
shape ADR-0010 already uses for the subscription path -- so duplicate delivery, replay, and
same-second/different-sequence collisions all resolve through the same proven `revenue_subscription_states`
unique constraint and `clearSupersededState` superseding logic, unchanged. Out-of-order protection comes
from `upsertDiscount`'s own version guard: a stale discount write is detected before any recalculation
is attempted (mirroring `upsertSubscription`'s pre-recalculation staleness check), so an older event
arriving after a newer one is a no-op at the ledger level and never reaches MRR recalculation at all.

### Customer-discount event outcomes

- Closes ADR-0010's documented known limitation for the create/update/delete cases where the
  underlying semantics are actually deterministic.
- No new infrastructure. `RevenueCalculationService` gains the narrow `customer_level`
  threading and `AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION` guard described above -- no other
  calculation semantics, currency handling, stacking behavior, or attribution changed, and PR
  #85's price-resolution strictness is untouched.
- A customer-level fixed-amount discount on a customer with multiple `active`/`past_due` subscriptions
  is explicitly unsupported (event `FAILED`), not silently approximated. Data-health/support tooling
  for surfacing `FAILED`/`UNSUPPORTED` rows to an operator is existing #15 territory, not new to this
  change.

### Subscription events and backfill

The complementary subscription path (`BillingLedgerUpsertService.activeCustomerDiscounts`) also
resolves any normalized customer-level discount whose effective window contains the subscription
state's own `effective_at`. This runs for accepted `customer.subscription.*` events and for
subscription backfill, so the customer-first backfill order cannot materialize plausible undiscounted
MRR. Subscription/item discounts from the payload are combined with the ledger discount; the same
Stripe discount, including an equivalent customer expansion represented in both places, is included
once. Genuinely distinct discounts remain stacked and therefore explicitly unsupported.

The lookup is scoped by both `workspace_id` and customer ID. Derived discount history records
customer-level scope so replay can preserve multi-subscription fixed-amount ambiguity without
inventing allocation semantics. Percentage discounts continue to compose independently across
subscriptions and currencies.

### Historical state: delete and update (amendment)

`billing_discounts` is a single upserted current-state row per `stripe_discount_id`, exactly like
every other `billing_*` ledger table -- it was never a history table. That is fine for
`CustomerDiscountMrrRecalculationService` itself, which only ever recalculates a customer's currently
affected subscriptions using the discount event's own `effective_at`. It is _not_ fine for
`activeCustomerDiscounts`, which answers a genuinely historical question: "what customer discount was
effective at this **other**, possibly older, subscription state's `effective_at`?" A delayed
`customer.subscription.created` webhook (arrived late, but carrying its own earlier provider second as
`effective_at`) can be recalculated after a `customer.discount.deleted` or `customer.discount.updated`
for the _same_ discount has already overwritten the one row `activeCustomerDiscounts` reads.

**Delete.** The original query filtered on `deleted = FALSE`, which answers "is this discount active
right now", not "was this discount active at `effective_at`". A discount deleted after an older
subscription's `effective_at` would be wrongly excluded from that subscription's recalculation --
producing plausible-but-stale MRR, the exact defect ADR-0004/#86 exists to prevent, just triggered by
`customer.subscription.*` recalculation instead of `customer.discount.*`. Fix: `end_at` is now always
populated once a discount is deleted (Stripe's own `end`, or -- when Stripe supplies none -- the same
provider-event-second fallback already used for `effective_at` above, persisted rather than only used
transiently), and `activeCustomerDiscounts` drops the `deleted` filter entirely in favor of the
temporal window alone (`start_at <= effective_at < end_at`). A deleted discount is included for any
`effective_at` before its actual deletion/end instant, and correctly excluded from it onward --
`deleted` no longer participates in the decision at all.

**Update.** Stripe's own semantics for `customer.discount.updated` are "occurs whenever a customer is
switched from one coupon to another" -- a genuine change of terms (percent/amount/currency) under the
same discount id, not a metadata touch. `billing_discounts` keeps only the current coupon's terms; the
prior coupon's are gone the instant the row is overwritten. If an older subscription's `effective_at`
predates that switch, the terms that actually applied then are not recoverable from normalized state --
and ADR-0004/#86 forbid guessing. A new `first_seen_start_at` column (this discount identity's
earliest-ever `start_at`, frozen at first insert, never touched by a later update) lets
`activeCustomerDiscounts` detect exactly this: a row it can find (`first_seen_start_at <= effective_at`)
but whose _current_ terms are provably not the ones in force at that `effective_at`
(`effective_at < start_at`). Rather than silently applying the wrong (current) terms, or silently
omitting the discount, that case throws `StripeBillingNormalizationException` -- the same
explicit-failure precedent this ADR already uses for cross-subscription fixed-amount ambiguity --
rolling the whole recalculation back atomically and marking the event `FAILED`/`UNSUPPORTED`:
inspectable and replayable, never a fabricated number.

This does not require reconstructing history from `stripe_webhook_events`: it never claims the old
terms, only refuses to substitute the new ones for them. Both changes are scoped to
`activeCustomerDiscounts` only -- `CustomerDiscountMrrRecalculationService`'s own recalculation (which
always resolves against the event's own `effective_at`, never an older one) and `currentDiscounts`
(subscription/item-level discounts, always fully re-supplied by the subscription's own payload) are
unaffected.

**Known limitation.** A discount identity that is switched (update) _before_ any subscription's
`effective_at` ever needed to resolve against its pre-switch window will never trip the ambiguity
guard -- and does not need to, because `activeCustomerDiscounts` is only ever asked to resolve
`effective_at`s that actually occur; this is not a gap, just a note that the guard is a correctness
backstop for the genuinely out-of-order case, not a general discount-history feature. Separately, a
discount whose very first accepted write for a given identity is (due to extreme out-of-order delivery)
already an "update" rather than the original "create" will record `first_seen_start_at` from that
first-accepted write, not from the true original creation -- an existing, pre-existing property of
every `billing_*` table's monotonic-version-guard design (the same is already true of, e.g.,
`billing_subscriptions.previousStatus`), not something newly introduced here.

## Consequences

- Both customer discount events and later subscription events/backfills synchronously materialize the
  same deterministic customer discount state.
- No new infrastructure, currency conversion, stacking behavior, or fixed-amount allocation rule is
  introduced.
- Duplicate, replayed, stale, and out-of-order inputs retain the ledger and revenue engine's existing
  convergence and atomic rollback behavior, including a customer discount deleted or switched (update)
  out of order relative to an older subscription recalculation: the older state either resolves
  correctly (delete) or fails explicitly rather than silently (update), never plausible-but-stale.
- `billing_discounts` remains a current-state table, not a history table; two narrow, targeted
  additions (`end_at` always populated on delete, `first_seen_start_at` frozen at first insert) close
  the specific historical-resolution gaps `activeCustomerDiscounts` has, without turning the table into
  a general audit log.
