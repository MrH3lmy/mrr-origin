# ADR-0010: Wire Stripe normalization into deterministic MRR calculation

- Status: Accepted
- Date: 2026-08-20

## Context

#12/#14 built two convergent, tested, idempotent pieces that never call each other in production:
`BillingLedgerUpsertService` (the single convergence point both `StripeWebhookNormalizationService`
and `StripeBackfillPageRunner` write normalized billing state through) and
`RevenueCalculationService.recordAndReplay` (the tested deterministic MRR engine that turns
subscription history into `customer_mrr_movements`/`customer_mrr_snapshots`). #83 found this gap:
real Stripe traffic normalizes billing state but never produces MRR rows.

Two orchestration shapes were on the table:

1. Synchronous calculation in/after the normalization transaction.
2. Asynchronous durable calculation, checkpointed after normalized billing state commits.

AGENTS.md forbids adding Kafka, Redis, or another queue before private beta. No existing outbox
table exists in this codebase to build (2) on without inventing new durable infrastructure, and (2)
would need its own checkpoint/idempotency machinery duplicating what `StripeWebhookNormalizationService`
already has.

## Decision

Calculate synchronously, in the same database transaction as the ledger write, triggered from
`BillingLedgerUpsertService.upsertSubscription` — already the one place both the webhook normalizer
(inside `StripeWebhookNormalizationService.applyAndMarkProcessed`'s transaction) and the backfill
page runner (inside `StripeBackfillPageRunner.applyPage`'s transaction) apply a subscription change.
Hooking the single convergence point, rather than each caller separately, guarantees webhook and
backfill trigger MRR recalculation identically with one code path.

`ARCHITECTURE.md`'s module table allows `revenue` to depend on `billing`, never the reverse. Calling
`RevenueCalculationService` directly from `billing` would invert that. Instead `billing` declares a
narrow port it owns, `BillingMrrRecalculationPort`, and calls it after a subscription upsert actually
applies (not on a stale/no-op write). `revenue` — already permitted to depend on `billing` — provides
the Spring bean implementing that port (`BillingMrrRecalculationAdapter`) and does the translation
into `RevenueModels.SubscriptionState`. `billing` source has no import of anything under
`com.mrrorigin.revenue`; the module table is unchanged.

Recalculation runs once per accepted `customer.subscription.*` normalization (webhook or backfill),
using that event's own parsed item/discount list — the "reconstructed current subscription state"
option from #83, narrowed to exactly the object the event just normalized, since a Stripe subscription
payload always carries its complete current item list. Each item's currency/amount/interval is
resolved from `billing_prices` (the ledger's own source of truth for price economics; subscription
item payloads only carry a price ID in this codebase's existing parser).

### `effective_at` derivation

`RevenueCalculationService` requires a non-null, provider-declared `effectiveAt` per state (ADR-0004);
it does not compute one itself — callers supply the historical instant a state took effect. Given the
subscription's previous status (read by `upsertSubscription` before applying) and the newly parsed
subscription, in priority order:

1. Trial → paid transition (previous status `trialing`, new status `active`/`past_due`): `trial_end`.
2. Entering `canceled` (new status `canceled`, previous status anything else): `canceled_at`, else
   `ended_at`.
3. First-observed state for a subscription not covered above: `trial_start` when trialing,
   `current_period_start` when active/past_due.
4. Otherwise (quantity/price/discount/interval edits with status unchanged, or a transition into
   `incomplete`/`incomplete_expired`/`unpaid`/`paused` with no more specific field, or resume): the
   event's own provider-declared second — `source_version`'s epoch second (Stripe's webhook `created`
   time, or backfill's conservative last-fully-elapsed-second) — exactly the ADR-0004-sanctioned
   fallback ("event creation... timestamps are fallback evidence only").

This adds no new MRR business rule; it only decides which already-provider-declared timestamp feeds
the existing engine for a given transition.

### Idempotency and ordering

`source_billing_reference` passed to `recordAndReplay` is
`{WEBHOOK|BACKFILL}:{source_version}:{source_sequence}:{stripe_subscription_id}` — globally unique per
accepted event per subscription. Both ordering-pair components are required: a backfill fetch's
`sequence` alone is only a nanosecond-within-second fraction (`BillingSourceVersion.forBackfillFetch`),
so two fetches in different seconds can share that fraction; including `version` (the whole second)
rules that out, and a backfill page's shared `sequence` bucket is additionally disambiguated by
subscription ID, since one page normalizes many subscriptions. `revenue_subscription_states` enforces
this uniqueness, so a redelivered/replayed event is a no-op insert (`ON CONFLICT DO NOTHING`) and
`recordAndReplay` re-derives the same movements/snapshots from the same history — no duplicate rows.
`StripeMrrRecalculationIntegrationTests.backfillFetchesInDifferentSecondsWithTheSameNanosecondFractionProduceDistinctMrrStates`
covers the collision directly.

Out-of-order delivery never regresses newer MRR state, but the mechanism is worth being precise about:
`BillingLedgerUpsertService.upsertSubscription`'s own pre-existing version guard
(`(source_version, source_sequence)`, #12) rejects a ledger write that is older than what
`billing_subscriptions` already holds _before_ recalculation is ever invoked -- a genuinely
out-of-order (older) event that arrives after a newer one has already applied is a no-op at the
ledger level, so `recalculateMrr` is never called for it at all. This means MRR history is exactly
as fine-grained as what the ledger's own convergence guarantee lets through: an older event that
_does_ still apply (nothing newer has landed yet) is inserted at its correct chronological
`effective_at` and `recordAndReplay` recalculates forward from there without disturbing later
history; an older event that arrives _after_ a newer one is safely dropped by the ledger before it
reaches MRR at all, so it can never regress or fragment the current MRR state. Either way, the
newer/current MRR snapshot is never regressed -- confirmed by test.

### Same-second, different-sequence collisions

`revenue_subscription_states` holds at most one row per `(subscription, effective_at)` -- correct per
ADR-0004: "equal effective timestamps are grouped before classification... producing at most one net
movement." Stripe's own provider timestamps only carry whole-second precision, so two genuinely
distinct, sequence-ordered changes to the same subscription can legitimately resolve to the same
second -- `BillingLedgerIdempotencyIntegrationTests` already proves this is a real, tested scenario at
the ledger layer, not a contrived edge case.

`BillingMrrRecalculationAdapter.clearSupersededState` handles it without ever fabricating a timestamp:
`effective_at` passed to `recordAndReplay` is always exactly the caller's provider-declared value.
When a _different_ event already occupies that exact `(subscription, effective_at)` slot, it is
deleted first (cascading to its items/discounts) so the incoming content becomes the sole record at
that instant; a true replay of the _same_ event (matching `source_billing_reference`) is left alone,
and `recordAndReplay`'s own `ON CONFLICT ... DO NOTHING` then correctly no-ops it.

This is deterministic regardless of delivery/processing order because
`BillingLedgerUpsertService.upsertSubscription`'s own `(source_version, source_sequence)` version guard
already only ever lets `recalculateMrr` be invoked, for one subscription, in non-decreasing
ordering-pair order -- a write that would regress relative to the currently stored ledger state is
rejected before reaching MRR recalculation at all. So whichever call happens to run last for a given
slot is always the ordering-pair winner, in either processing order:
`StripeMrrRecalculationIntegrationTests.sameSecondDifferentSequenceSubscriptionChangesConvergeToIdenticalMrrRegardlessOfProcessingOrder`
proves both the forward and reversed delivery order of the same two events converge to byte-identical
movements and snapshots. (An earlier version of this adapter nudged the later state's timestamp forward
by a microsecond instead of clearing the superseded row; that fabricated a timestamp ADR-0004 does not
sanction and made history depend on processing order -- caught in review before merge.)

### Usage-derived pricing

ADR-0004 requires metered/tiered recurring prices to fail visibly as `UNSUPPORTED_USAGE_PRICING`
rather than being calculated as ordinary MRR. A metered price can still carry a non-null
`unit_amount` (the per-unit rate), so `unit_amount` presence alone cannot distinguish it from a fixed
recurring charge -- an earlier version of this change assumed it could and always passed
`usagePricing=false`, caught in review before merge. V25 adds `billing_prices.usage_type` (Stripe's
`recurring.usage_type`, `licensed` or `metered`); `BillingLedgerUpsertService.isUsagePricing` flags an
item as usage-derived when the price's `usage_type` is `metered` or its `billing_scheme` is `tiered`.
`StripeMrrRecalculationIntegrationTests.meteredRecurringPriceWithNonNullUnitAmountFailsVisiblyInsteadOfFabricatingMrr`
proves a metered price with a non-null `unit_amount` produces a visible `UNSUPPORTED_USAGE_PRICING`
snapshot, not a fabricated MRR number.

### Failure and recovery boundary

The recalculation call sits inside the same transaction as the ledger write and the
webhook lease/checkpoint update (`applyAndMarkProcessed`) or the backfill checkpoint advance
(`applyPage`). A failure at any point — price lookup, effective-at derivation, or the MRR replay
itself — rolls back the whole transaction: the ledger write, the lease/checkpoint move, and any
partial MRR write are all undone together. The event stays `PENDING`/the backfill page stays
un-advanced, so the existing retry path (webhook lease expiry re-claim; backfill page re-fetch on the
next `runBatch`) reprocesses it from scratch. No partially-committed billing/MRR state is possible.

## Consequences

- No new infrastructure; reuses the two existing retry-safe workers as the durability mechanism.
- Webhook and backfill normalization transactions run measurably longer (the customer's full MRR
  history replay, already the tested engine's own cost model) — acceptable at V1/private-beta scale;
  a customer with unusually long billing history is the one case worth watching operationally.
- Real Stripe traffic now produces `customer_mrr_movements`/`customer_mrr_snapshots` without changing
  `RevenueCalculationService`'s calculation semantics.

## Known limitations (reported, not silently papered over)

- Legacy top-level `customer.discount.*` webhook events (the customer's singular legacy `discount`
  field, distinct from a subscription's compound `discounts` array) are not wired to recalculation.
  These are customer-scoped, not subscription-scoped, in this codebase's parser
  (`StripeBillingObjectParser.parseNestedDiscount`/`parseTopLevelDiscount`); subscription-level and
  item-level discounts arrive embedded in `customer.subscription.*` events and are covered. A
  workspace relying on the legacy customer-level discount field would not see its MRR effect until a
  later subscription-touching event recalculates.

`usagePricing` detection and the same-second collision handling were both caught and fixed during
review (see the dedicated sections above and V25's `billing_prices.usage_type` column) rather than
left as limitations.
