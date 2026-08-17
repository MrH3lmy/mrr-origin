# Weekly action summary and CSV export (#26) — contract-first plan

## Status

v1 decisions resolved (below) by the issue owner on 2026-08-17. This document
is the frozen contract for implementation. No summary generation, delivery,
or export code has been implemented yet — this update is for a short
contract review before implementation code begins.

## What this reuses (no new calculation rules)

- **Movements and deltas**: `SourceComparisonService` (#23) and
  `RevenueOverviewService` New/Churned MRR by source/campaign/page, already
  keyed by `(dimensionValue, attributed, currency)`.
- **Retained MRR / NRR join**: `RetentionCohortService#summary` (#25) joined
  by the same key, exactly as `RevenueOverviewController#comparison` already
  does for the UI — the export reuses that join, it does not reimplement it.
- **Cohort heatmap**: `RetentionCohortService#heatmap` for the retention
  cohorts export, including its `AgeCell.unavailable(REASON_MATURITY_PENDING)`
  contract — immature cells stay unavailable, never zero.
- **Customer directory and evidence**: `CustomerDirectoryService` (#24) for
  the customers export, and `CustomerTimelineService`'s
  `canViewSensitiveIdentity` / `WorkspaceContext#canManage` redaction gate
  from PR #57 — reused verbatim, not a parallel redaction rule.
- **Ownership/tenancy**: `RevenueMovementsService#OWNER_CTE`, the same
  workspace/project ownership fragment used by every existing reporting read
  model.
- **UNATTRIBUTED vs. attributed-but-missing-field**: preserved and made
  explicit as CSV tokens (see below) rather than collapsed to a blank cell.

## v1 decisions (resolved)

### 1. Low-volume handling

- Fixed threshold: **5 customers** per dimension bucket and currency.
- Exports and drill-downs always show exact totals — the threshold never
  removes or merges a bucket, and never affects CSV row content.
- Only the weekly-summary _insight text_ is affected: below threshold, the
  insight's `status` is `INSUFFICIENT_SAMPLE` and comparative/ranking
  language ("up 32%", "your best source") is suppressed in favor of a plain
  factual statement ("New MRR from X: $Y, 3 customers — too few to compare
  week over week").

### 2. Material change detection

- Compares the last completed project-timezone week against the immediately
  preceding completed week. "Completed" means the week's end boundary
  (project-timezone Monday 00:00, see Weekly boundary below) has passed at
  generation time.
- Applies only to **New MRR** and **Churned MRR** per `(dimension, bucket,
currency)` — the two movement-based metrics that don't require cohort
  maturity. Retained MRR / NRR are exposed in the retention-cohorts export
  but are not part of week-over-week delta language, since a one-week-old
  cohort cannot be 30 days mature.
- Statuses:
  - `NEWLY_APPEARED`: prior = 0, current > 0, gated by current week's
    customer count ≥ 5.
  - `DISAPPEARED`: current = 0, prior > 0, gated by prior week's customer
    count ≥ 5.
  - `MATERIAL_CHANGE`: both nonzero, `abs(current - prior) / prior ≥ 0.25`,
    gated by `min(current week count, prior week count) ≥ 5`.
  - `INSUFFICIENT_SAMPLE`: the applicable count (per the gating above) is
    < 5. Checked before `STABLE`.
  - `STABLE`: none of the above; sample is sufficient but change is < 25%.
  - Never described as a statistically detected anomaly (no stddev/z-score
    language anywhere in copy).
- **Confirmed interpretation**: "the applicable customer count" for an
  ordinary (non-zero-crossing) comparison is
  `min(currentWeekCount, priorWeekCount)` — if either week's sample is thin,
  the comparison is unreliable regardless of the other week's size.

### 3. Delivery scope

- #26 delivers a `WeeklySummary` DTO plus text and HTML presentation of it.
  No email provider, scheduler, job-lease, or recipient-selection
  infrastructure is added in this PR.
- Follow-up issue **#59** (created) tracks: scheduling/cadence, email
  provider selection and integration (needs an ADR per `AGENTS.md`), delivery
  retry/lease semantics, and recipient selection.
- PR #58 references #26 (`Refs #26`, not `Closes #26`) and will not close it
  while #26's acceptance criteria still names delivery/opt-out — those
  criteria are satisfied by #59, not by this PR. Whoever reviews should
  either amend #26's acceptance criteria to descope delivery/opt-out to #59,
  or keep #26 open until both PRs land; this doc doesn't decide that call.

#### 3a. `WeeklySummary` DTO and endpoint contract

`GET /api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary`
(optional `weekStart` query param, an ISO date in the project's timezone,
defaulting to the last completed week per the Weekly boundary section below).
Requires `WorkspaceContext#requireMembership`, same as every other reporting
endpoint. Response:

```
WeeklySummaryResponse {
  workspaceId, projectId
  timezone                 // Project.timezone, IANA zone id, echoed verbatim
  weekStart, weekEnd        // UTC instants for the completed week, [weekStart, weekEnd)
  priorWeekStart, priorWeekEnd
  generatedAt               // server clock, injected Clock (RetentionCohortService's pattern)
  currencySections: [ CurrencySection ]   // one per currency with any NEW/CHURN
                                            // movement in either week; ordered currency ASC
}

CurrencySection {
  currency
  insights: [ Insight ]     // ordered dimension ASC, parent source/campaign
                             // hierarchy ASC, dimension_value ASC (real values
                             // before NONE before UNATTRIBUTED), movementType
                             // (NEW before CHURN) -- deterministic
}

Insight {
  dimension                 // SOURCE | CAMPAIGN | LANDING_PAGE
  dimensionValue            // null when bucketed
  dimensionBucket           // NONE | UNATTRIBUTED | null (same three-state contract as the CSV)
  movementType               // NEW | CHURN
  currentAmountMinor, currentCustomerCount
  priorAmountMinor, priorCustomerCount
  percentageChange           // nullable Double (ratio, e.g. 0.30); null exactly when
                              // priorAmountMinor = 0 (mathematically undefined), so
                              // NEWLY_APPEARED is null; DISAPPEARED is -1.0
  applicableCustomerCount    // the count actually used for the §2 threshold gate --
                              // min(currentCustomerCount, priorCustomerCount) except
                              // NEWLY_APPEARED (= currentCustomerCount) and
                              // DISAPPEARED (= priorCustomerCount) -- exposed so tests
                              // and clients don't have to re-derive the gating rule
  status                     // MATERIAL_CHANGE | NEWLY_APPEARED | DISAPPEARED |
                              // INSUFFICIENT_SAMPLE | STABLE
  currentEvidenceFilters {    // exact RevenueMovementsService filter contract
    from, to, movementType, currency
    source, sourceUnattributed, sourceMissing
    campaign, campaignMissing
    landingPage, landingPageMissing
  }
  priorEvidenceFilters {       // same hierarchy/type/currency; prior week's from/to
    from, to, movementType, currency
    source, sourceUnattributed, sourceMissing
    campaign, campaignMissing
    landingPage, landingPageMissing
  }
  currentEvidenceLink          // current-week dashboard evidence
  priorEvidenceLink            // prior-week dashboard evidence
}
```

One `Insight` per `(dimension, parent filter hierarchy,
dimensionValue-or-bucket, movementType)` that has a nonzero amount in the current week, the prior week, or both -- the same
population `comparison-v1`'s pivoted rows would cover for New/Churned MRR, so
the DTO and the CSV export reconcile against the same underlying query. No
insight is dropped for being `STABLE` or `INSUFFICIENT_SAMPLE`: every
qualifying bucket gets a statement, per the issue's "every summary statement
links to a dashboard view" requirement -- suppression per §1 changes an
insight's _language_, never its presence.

Each insight carries the complete parent hierarchy in both
`currentEvidenceFilters` and `priorEvidenceFilters`. They differ only in
their `from`/`to` week boundaries. Campaign evidence includes its real parent `source`; landing-page evidence
includes its real parent `source` plus either `campaign` or
`campaignMissing=true`. SOURCE buckets use `sourceMissing=true` for
`NONE` and `sourceUnattributed=true` for `UNATTRIBUTED`. CAMPAIGN and
LANDING_PAGE missing buckets use `campaignMissing=true` and
`landingPageMissing=true`, respectively. Bucket labels are never sent as
sentinel values in a string filter, so real values such as `NONE` or
`UNATTRIBUTED` cannot collide with a bucket.

`currentEvidenceLink` and `priorEvidenceLink` are relative paths into the
Sources screen (#23) carrying those exact filters, with `from`/`to` set to
the applicable current or prior week. The implementation must widen the
Sources page's `searchParams` contract to accept and initialize
`movementType`, `currency`, `source`, `sourceUnattributed`,
`sourceMissing`, `campaign`, `campaignMissing`, `landingPage`, and
`landingPageMissing`, using the same explicit boolean modes as
`RevenueMovementsService`. Opening either link must preselect the exact
comparison cell and widened movement drill-down whose total and row count
reconcile to that side of the insight.

**Text/HTML rendering contract**: both are pure functions over
`WeeklySummaryResponse`, one section per currency, one line per insight
_except_ `STABLE` insights, which are omitted from the rendered narrative
(they remain in the DTO/JSON for reconciliation) and rolled up into a single
trailing line per currency section ("N other comparison signals were stable this week")
linking to the full `comparison-v1`/Sources view for that period and
currency. Every rendered non-`STABLE` statement exposes both current- and
prior-week evidence links. Line copy follows §1/§2's wording rules verbatim (no "anomaly"
language, `INSUFFICIENT_SAMPLE` insights use the flat factual phrasing from
§1, never comparative language). HTML output is the same line structure
wrapped in the existing `apps/web` component styling conventions, not a
separate copy deck.

### 4. Opt-out

- No opt-out/unsubscribe persistence, UI, or API is added in this PR — there
  is no delivery job yet to opt out of.
- Per-member opt-out semantics and controls belong to #59.

### 5. CSV contract

Three separate v1 exports, each: UTF-8, RFC 4180 (CRLF line endings, quoted
fields containing comma/quote/CRLF, `""` escaped quotes), header row, a fixed
documented column order, and an `X-Export-Schema-Version` response header
(`comparison-v1` / `retention-cohorts-v1` / `customers-v1`).

Shared conventions across all three:

- Money is `..._amount_minor` (integer, matches `RevenueModels`/storage
  convention) paired with a `currency` column (ISO 4217). No cross-currency
  totals or blended rows ever.
- A dimension bucket is rendered as one of three mutually exclusive forms so
  a blank cell is never ambiguous:
  - a real captured value in `dimension_value`, with `dimension_bucket` blank;
  - `dimension_bucket = NONE` (evidence exists, this field wasn't captured —
    e.g. direct traffic with no UTM source, or a campaign with no landing
    page tag), `dimension_value` blank;
  - `dimension_bucket = UNATTRIBUTED` (no acceptable evidence at all — SOURCE
    dimension only), `dimension_value` blank.
  - `NONE` and `UNATTRIBUTED` are never coalesced into one bucket or into a
    plain blank cell.
- Unavailable retained MRR / retention % / NRR: an `..._available` boolean
  column plus the value column left **blank** (never `0`), plus
  `unavailable_reason` naming which of the two stable reasons applies --
  `MATURITY_PENDING` (a cohort exists but hasn't reached the requested age,
  per `RetentionCohortService.REASON_MATURITY_PENDING`) or
  `NO_ACQUISITION_COHORT` (no retention row exists for this key at all, per
  `RevenueOverviewController#unavailableMetrics`'s identical reason code).
  Both leave every numeric field of the row's retained-MRR/retention-
  percentage/NRR blank; `retained_mrr_available`,
  `retention_percentage_available`, and `nrr_available` are all `false`
  (and are always identical for a row) -- the two reasons only
  differ in _why_, never in the shape of the unavailable value.
- Every row carries an `evidence_link`: a relative dashboard path (workspace/
  project/period/dimension/currency filter) that reconciles to that exact
  row, reusing the same filter parameters the row was queried with.
- Generation is synchronous and streamed directly to the HTTP response as
  rows are read from the database — no full CSV string is ever built in
  memory, and no generated file is persisted to disk or storage.
- Money/customer-count/percentage values in the CSV must always equal the
  same query's non-CSV JSON response for identical filters (reconciliation
  requirement below).

#### 5a. `comparison-v1` — one row per `(dimension bucket, currency)`

Reuses `SourceComparisonService.compare` (both movement types, pivoted like
`ComparisonTable.tsx`'s `pivot()`) joined with `RetentionCohortService
.summary` at one caller-selected `retentionAgeDays`, exactly as
`RevenueOverviewController#comparison` already joins them.

Ordered columns:

1. `dimension` — `SOURCE` \| `CAMPAIGN` \| `LANDING_PAGE`
2. `dimension_value`
3. `dimension_bucket` — `NONE` \| `UNATTRIBUTED` \| blank
4. `currency`
5. `period_start` — ISO 8601 UTC instant, inclusive
6. `period_end` — ISO 8601 UTC instant, exclusive
7. `new_mrr_amount_minor`
8. `new_mrr_customer_count`
9. `churned_mrr_amount_minor`
10. `churned_mrr_customer_count`
11. `retention_age_days` — `30` \| `60` \| `90` (single value, caller-selected)
12. `retained_mrr_available`
13. `retained_mrr_amount_minor` — blank when unavailable
14. `retention_percentage_available`
15. `retention_percentage` — ratio (e.g. `0.85`), blank when unavailable
16. `nrr_available`
17. `nrr` — ratio, blank when unavailable
18. `unavailable_reason` — `MATURITY_PENDING` \| `NO_ACQUISITION_COHORT` \|
    blank
19. `evidence_link`

Row order: `currency ASC`, then `dimension_value ASC` (real values before
`NONE` before `UNATTRIBUTED`).

#### 5b. `retention-cohorts-v1` — one row per `(dimension bucket, currency, acquisition period)`

Reuses `RetentionCohortService.heatmap` (`CohortRow`) verbatim.

Ordered columns:

1. `dimension`
2. `dimension_value`
3. `dimension_bucket` — `NONE` \| `UNATTRIBUTED` \| blank
4. `currency`
5. `period_start` — acquisition period start (UTC calendar month, ADR-0006)
6. `period_end`
7. `starting_mrr_amount_minor`
8. `sample_size`
9. `age30_available`
10. `age30_retained_mrr_amount_minor`
11. `age30_retention_percentage`
12. `age30_expansion_mrr_amount_minor`
13. `age30_contraction_mrr_amount_minor`
14. `age30_churn_mrr_amount_minor`
15. `age30_reactivation_mrr_amount_minor`
16. `age30_nrr`
17. `age30_unavailable_reason`
18. `age60_available`
19. `age60_retained_mrr_amount_minor`
20. `age60_retention_percentage`
21. `age60_expansion_mrr_amount_minor`
22. `age60_contraction_mrr_amount_minor`
23. `age60_churn_mrr_amount_minor`
24. `age60_reactivation_mrr_amount_minor`
25. `age60_nrr`
26. `age60_unavailable_reason`
27. `age90_available`
28. `age90_retained_mrr_amount_minor`
29. `age90_retention_percentage`
30. `age90_expansion_mrr_amount_minor`
31. `age90_contraction_mrr_amount_minor`
32. `age90_churn_mrr_amount_minor`
33. `age90_reactivation_mrr_amount_minor`
34. `age90_nrr`
35. `age90_unavailable_reason`
36. `evidence_link`

Row order: `currency ASC`, then `period_start ASC`, then `dimension_value ASC`
(real values before `NONE` before `UNATTRIBUTED`) — matches `CohortRow.ORDER`
within a currency.

#### 5c. `customers-v1` — one row per `(customer, currency with current MRR)`, or one row with blank currency for a customer with no current MRR in any currency

Reuses `CustomerDirectoryService` (full cursor-paginated scan, not the
default-25 page) plus `CustomerTimelineService`'s redaction gate applied to
`external_user_id`.

Ordered columns:

1. `stripe_customer_id`
2. `deleted`
3. `provider_created_at`
4. `acquisition_effective_at` — blank if no `NEW` movement yet
5. `acquisition_confidence` — `STRONG` \| `UNATTRIBUTED` \| blank. These are
   the only two values `AttributionApplicationService` (the authoritative
   attribution model) actually emits today; `VERIFIED`/`MODERATE` are not
   produced and are not promised by this v1 schema. Blank means there is no
   acquisition movement yet, or attribution hasn't been (re)calculated under
   the current model version — an operational gap per
   `CustomerDirectoryService.Entry`'s own doc comment, never a fabricated
   result.
6. `unattributed_reason` — blank when not applicable
7. `first_source`
8. `first_source_bucket` — `NONE` \| `UNATTRIBUTED` \| blank (blank exactly
   when `acquisition_confidence` is blank)
9. `currency` — blank when this customer has no current MRR in any currency
10. `current_mrr_amount_minor` — blank when `currency` is blank
11. `subscription_statuses` — `;`-joined, sorted, e.g. `active;past_due`
12. `external_user_id` — populated only when the exporting caller has
    `WorkspaceContext#canManage`; blank otherwise (redaction, PR #57), and
    blank regardless of role when the customer has no active identity link
13. `evidence_link` — path to this customer's timeline view

**Row order (implementation note, deviates from the original draft above)**:
`provider_created_at DESC, stripe_customer_id DESC` is the primary order --
matching `CustomerDirectoryService`'s own cursor order -- with `currency ASC
NULLS LAST` only as a tiebreak among one customer's own (typically one or
two) currency rows. This is the one export whose row count isn't bounded by
a small dimension cardinality, so it's also the one that must stream
page-by-page from the database rather than materialize a full list first;
sorting by currency globally would require buffering every customer before
writing the first row, which directly conflicts with "stream large exports,
never build an unbounded in-memory CSV." Ordering is still fully
deterministic, just not currency-primary the way `comparison-v1` and
`retention-cohorts-v1` are (both genuinely bounded by dimension
cardinality, so currency-primary ordering there costs nothing).

#### Export audit

New append-only `export_audit_log` table, modeled on
`stripe_customer_link_repair_audit_log` (V17): `id`, `workspace_id`,
`project_id`, `export_type` (`COMPARISON` \| `RETENTION_COHORTS` \|
`CUSTOMERS`), `schema_version`, `actor_subject_id`, `filters` (JSONB —
query parameters only: period, dimension, source/campaign, currency,
retention age — never row-level or customer data), `row_count`, `created_at`.
FK `(project_id, workspace_id) → projects(id, workspace_id) ON DELETE
CASCADE`, matching V17's own cascade — this is what makes audit metadata
retained "for the lifetime of the workspace, deleted with the workspace"
without a separate cleanup job.

### 6. Currency

- Every export and every summary section is scoped to one currency at a
  time; no conversion, reporting currency, or blended cross-currency
  ranking anywhere in v1.
- Multi-currency workspaces get independent sections/row-groups per
  currency, ordered `currency ASC`.

## Weekly boundary

- "Week" = Monday 00:00 (project timezone, `Project.timezone`, IANA zone id)
  through the following Monday 00:00, exclusive — i.e. `[weekStart,
weekStart + 7 days)` in the project's zone, converted to UTC instants for
  querying `effective_at` exactly like every existing reporting service's
  `[from, to)` convention.
- "Last completed week" is the most recent such window whose end boundary is
  not after `now` (server clock, injected `Clock` per this codebase's
  existing pattern in `RetentionCohortService`).

## Authorization

- Export endpoints require workspace membership (`WorkspaceContext
#requireMembership`), matching every other reporting read endpoint —
  tenant/project-scoped like the rest of `reporting`.
- `external_user_id` in the customers export additionally requires
  `#canManage` (OWNER/ADMIN), per PR #57's redaction rule; VIEWER/MEMBER
  callers get a fully valid, fully reconciling export with that one column
  blank, not a 403.

## Follow-up issue split

Created **#59** — "Weekly summary delivery: scheduling, provider, retry, opt-out, recipients" — carrying everything #26 explicitly excludes in v1: email provider integration and ADR, scheduled trigger/job-lease semantics, delivery retry, recipient selection, and per-member opt-out persistence/UI/API.
