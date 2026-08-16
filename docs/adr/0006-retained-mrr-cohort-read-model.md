# ADR-0006: Retained-MRR cohort read model (#25)

- Status: Accepted
- Date: 2026-08-16

## Context

ADR-0004 already defines the calculation contract for Retained MRR and NRR:

> Retained MRR is the sum, per currency, of a fixed acquisition cohort's customer MRR
> at the selected cohort age. It includes expansion and reactivation that is active at
> that age and is zero for churned customers. Cohort membership never changes after
> acquisition.
>
> NRR is calculated per currency and fixed customer cohort for a half-open window
> `[start, end)`: `(starting MRR + expansion - contraction - churn) / starting MRR`.
> New and reactivation movements are excluded. Retained MRR at `end` is an equivalent
> cross-check when no movement occurs exactly at the excluded endpoint.
>
> NRR is undefined when starting MRR is zero. Cross-currency totals and NRR are
> unsupported in V1 because no reporting-currency/FX policy is approved.

That decides the arithmetic. It does not decide: how customers are grouped into a
cohort, what "acquisition start period" means, when a 30/60/90-day age is mature
enough to show, or how the read model stays correct as attribution recalculates. #25
needs those decisions before it can produce a single number, and none of them are
written down anywhere else.

## Decision

### Cohort key and membership

A cohort member is one `(stripe_customer_id, currency)` pair with a `NEW` movement
under `mrr-v1` (exactly one exists per pair, by construction of the movement engine).
A member's cohort is fixed by:

- **Owning project** — resolved with the same `OWNER_CTE` used by #22/#23, so a
  customer can never contribute to two projects' cohorts at once, and a relink moves
  their entire cohort membership to the new owning project on the next read.
- **Acquisition start period** — the UTC calendar month containing the member's `NEW`
  movement `effective_at`. Not specified anywhere else; monthly is chosen as the
  smallest period that stays legible as 30/60/90-day columns without single-digit
  sample sizes at V1's expected data volume, matching standard SaaS cohort tables.
- **Source / campaign / landing page** — the `customer_attribution_results` row
  attached to the member's own `NEW` movement (`attribution-v1`), read the same way
  #22/#23 already read it. `confidence != STRONG` places the member in the
  `UNATTRIBUTED` source bucket; a `STRONG` member with no captured value at a given
  dimension goes in that dimension's `MISSING` bucket. These are explicit bucket
  modes (`ATTRIBUTED` / `UNATTRIBUTED` / `MISSING`), not string sentinels, so a real
  source or campaign literally named `"UNATTRIBUTED"` or `"NONE"` can never collide
  with them — the same rule #23 already applies to `null` dimension values. Campaign
  and landing-page dimensions only exist within an already-`ATTRIBUTED` source, same
  as the #23 drill-down hierarchy.
- **Currency** — the member's own acquisition currency. Currencies are never mixed or
  summed across cohorts.

"Cohort membership never changes after acquisition" (ADR-0004) is about the
acquisition instant used as the age anchor, which is immutable once the `NEW`
movement is replayed. It is not a claim that the source/campaign/page bucket is
frozen: attribution is versioned and recalculable (ADR-0005), so a member's bucket
can change when recalculation improves their evidence. Because this read model is
computed live from current `customer_mrr_movements` / `customer_attribution_results`
state on every request — no materialized table — a recalculation or a late-arriving
touchpoint is reflected on the very next read with no invalidation step to get wrong.
Materialization is deliberately not introduced: #22, #23, and the unattributed inbox
already prove this read-model-over-derived-tables pattern at V1's expected scale, and
ARCHITECTURE.md requires an approval before adding new infrastructure to keep a
materialized view correct.

### Age maturity

A cohort's `(period, age)` cell is available only once **every possible member** of
that period could have reached that age: `now >= periodEnd + age`, where `periodEnd`
is the first instant after the acquisition month. Below that boundary the cell is
`UNAVAILABLE` with a `reason` of `MATURITY_PENDING` — never a numeric zero, and never
a partial average over whichever members happen to have individually crossed their
own boundary first. This keeps a mature cell's membership and values stable forever
once computed (barring recalculation), and keeps the same nominal cell from silently
changing shape between two reads taken hours apart. `startingMrr` and `sampleSize`
are properties of acquisition, not of age, so they are always available regardless of
maturity; only the per-age outcome fields (`retainedMrr`, `retentionPercentage`,
`expansionMrr`, `contractionMrr`, `churnMrr`, `reactivationMrr`, `nrr`) are gated.

Within a mature cell, each member is evaluated at their own `acquisitionAt + age`
cutoff (not the period boundary), matching ADR-0004's per-cohort NRR window applied
per member and summed. `retainedMrr` for a member is the signed sum of that member's
movement amounts with `effective_at <= cutoff` (inclusive, i.e. the balance "at end").
`expansionMrr` / `contractionMrr` / `churnMrr` / `reactivationMrr` are the same
member's movement amounts, split by type, with `effective_at` in the half-open window
`(acquisitionAt, cutoff)` — `NEW` is excluded by type (it is `startingMrr`), matching
ADR-0004's "New and reactivation movements are excluded" for the NRR sum specifically
(`reactivationMrr` is still reported as its own reconciliation field; it is simply not
one of the terms in the NRR ratio). Per ADR-0004, a movement landing exactly on the
`cutoff` instant is included in `retainedMrr` but excluded from the NRR window sum;
`retainedMrr` and the NRR-implied balance can then differ by that one movement. This
is the documented ADR-0004 behavior, not a new rule.

### Reported fields per `(cohort, age)`

`startingMrr`, `sampleSize` (distinct members), and — when the age is mature —
`retainedMrr`, `retentionPercentage` (`retainedMrr / startingMrr`), `expansionMrr`,
`contractionMrr`, `churnMrr`, `reactivationMrr`, and `nrr`
(`(startingMrr + expansionMrr - contractionMrr - churnMrr) / startingMrr`, per
ADR-0004). `startingMrr` is always the sum of positive `NEW` amounts for a non-empty
cohort, so `retentionPercentage`/`nrr` only hit a zero denominator for an empty
cohort (`sampleSize == 0`); the read model never emits a row for an empty cohort key,
and the underlying pure calculation returns an explicit "unavailable" ratio rather
than dividing by zero, so the empty-cohort case is exercised directly as a unit test
without depending on that emission behavior.

### Sources (#23) integration

The Sources comparison aggregates New/Churned MRR over an arbitrary `[from, to)`
range, not one acquisition period. Its Retained MRR / NRR columns therefore aggregate
every acquisition-period cohort whose period falls inside `[from, to)` for the
selected dimension value, at a caller-selected age (30/60/90, default 30). The
combined row is available only when every contributing period is itself mature at
that age (same all-or-nothing rule as a single cohort cell, applied across periods);
otherwise it stays `UNAVAILABLE`, and #23's existing per-response `unavailableMetrics`
reason list is extended to describe _why_ (`MATURITY_PENDING` vs. no acquisition in
range at all) instead of the current permanent "not built yet" reason.

## Consequences

- Cohort cells never move once mature, but the read model has no cache to bust when
  attribution recalculates or late billing events replay — correct-by-construction
  instead of correct-by-invalidation.
- Monthly acquisition periods mean a 30-day age can be gated by up to ~60 days of
  wall-clock time after the first member of a period acquires (a member acquired on
  the 1st waits the full month plus 30 days before their period-mate acquired on the
  30th does). This trades a slower first reveal for a cell that is stable the moment
  it appears. A future issue may add a rolling/no-gap period option if this proves too
  conservative for founders with low acquisition volume.
- `reactivationMrr` is exposed as its own field even though it is not itself a
  required issue-#25 output, because ADR-0004's `retainedMrr` definition depends on
  it and every aggregate must reconcile to movement-level evidence; without it,
  `retainedMrr` for a churn-then-reactivate member would not visibly reconcile to
  `startingMrr + expansionMrr - contractionMrr - churnMrr`.
