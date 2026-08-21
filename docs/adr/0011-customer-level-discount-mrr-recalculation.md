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
the subscription path already uses. No change to `RevenueCalculationService`, `RevenueModels`, or
`BillingMrrRecalculationAdapter`: this is new orchestration in `billing`, not a new calculation rule.

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
expansion/reactivation on its own; no new movement logic was needed.

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

## Consequences

- Closes ADR-0010's documented known limitation for the create/update/delete cases where the
  underlying semantics are actually deterministic.
- No new infrastructure; no change to `RevenueCalculationService`'s calculation semantics, to
  attribution, or to PR #85's price-resolution strictness.
- A customer-level fixed-amount discount on a customer with multiple `active`/`past_due` subscriptions
  is explicitly unsupported (event `FAILED`), not silently approximated. Data-health/support tooling
  for surfacing `FAILED`/`UNSUPPORTED` rows to an operator is existing #15 territory, not new to this
  change.

## Known limitation (reported, not silently papered over)

A customer-level discount created _before_ a customer's subscription starts is not retroactively
applied by that subscription's own first `customer.subscription.created` recalculation, which builds
its discount list only from that event's own embedded `discounts` array (PR #85) and has no reason
today to also look up a pre-existing customer-level discount. Closing this would mean changing
`BillingLedgerUpsertService.upsertSubscription`'s already-reviewed recalculation path, which is outside
#86's scope (`customer.discount.*` wiring, not `customer.subscription.*` wiring). This is a narrow edge
case in practice -- it requires a workspace using the legacy customer-level discount field _and_
creating a new subscription for that customer while the discount is still active -- but it is real and
is called out here rather than left implicit.
