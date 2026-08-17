# Weekly action summary and CSV export (#26) — contract-first plan

## Status

Draft. Blocked on product/architecture decisions listed below. No summary
generation, delivery, or export code has been implemented yet.

## What this reuses (no new calculation rules)

- **Movements and deltas**: `SourceComparisonService` (#23) and
  `RevenueOverviewService` New/Churned MRR by source/campaign/page, already
  keyed by `(dimensionValue, attributed, currency)`.
- **Retention**: `RetentionCohortService` (#25) starting/retained MRR,
  retention %, NRR, and its `AgeCell.unavailable(REASON_MATURITY_PENDING)`
  pattern for immature cohorts — the summary must reuse this "unavailable,
  never zero" contract rather than reinvent it.
- **Customer evidence**: `CustomerTimelineService` / `CustomerDirectoryService`
  (#24), including the `canViewSensitiveIdentity` / `WorkspaceContext#canManage`
  redaction gate introduced in PR #57. Any export touching customer identity
  fields must apply the same redaction, not a parallel rule.
- **Ownership/tenancy**: `RevenueMovementsService#OWNER_CTE`, the same
  workspace/project ownership fragment used by every existing reporting read
  model.
- **UNATTRIBUTED vs. attributed-but-missing-field**: `SourceComparisonService`
  already distinguishes `attributed = false` (no STRONG evidence at all) from
  `attributed = true` with a null dimension value (evidence exists, this field
  wasn't captured). The summary and export must preserve this distinction
  rather than collapsing both to one "unknown" bucket.

## Proposed shape (pending the decisions below)

- A read-only weekly summary view: statements computed from the above
  services' outputs, each one carrying a link to the exact dashboard filter
  (period + dimension + currency) that produced it — no summary line without
  an evidence link.
- A CSV export endpoint per already-authorized reporting view (source
  comparison, retention cohorts, customer directory), streamed/paginated
  from the database rather than buffered fully in memory, workspace/project
  scoped and role-authorized the same way the read APIs are.
- An export audit entry (event type, actor, workspace/project, view,
  filters, row count, timestamp) recorded per export, following the existing
  `CustomerLinkRepairAuditService` precedent for "audit without logging the
  sensitive payload" — exported customer data itself is never written to the
  audit log.

## Open product/architecture decisions (blocking implementation)

None of the following are defined in `PRODUCT.md`, `ARCHITECTURE.md`, the
ADRs, or the existing codebase (verified: no opt-out/unsubscribe field or
table exists anywhere; `com.mrrorigin.notification` is still an empty
package stub; no CSV export exists in `apps/api`).

1. **Low-volume suppression thresholds** — issue #26 requires suppressing or
   labeling "low-volume noise," but no minimum sample size, dollar amount, or
   percentage is specified anywhere, nor whether it's a fixed constant or a
   per-workspace setting.
2. **Anomaly thresholds** — "material anomalies" has no statistical
   definition (absolute delta, % change, stddev-based, etc.) and no
   precedent in this codebase.
3. **Delivery scheduling semantics** — the `notification` module has no
   scheduler, job-lease pattern, or email provider today. Issue #26 asks for
   an "email-ready summary" but delivery cadence, timezone-anchored trigger
   time, and provider are all undecided. Adding a scheduler/provider without
   an ADR would violate `AGENTS.md`'s architecture rules.
4. **Opt-out behavior** — no opt-out/unsubscribe persistence exists at any
   level (workspace, project, or member). Needs a data model decision and an
   enforcement point before a delivery job can honor it.
5. **CSV schemas and retention periods** — no column list, schema version
   strategy, or file/audit-entry retention period is defined for any export.
6. **Reporting-currency conversion** — `ARCHITECTURE.md` lists this as an
   explicit deferred decision. The summary must therefore present per-currency
   sections rather than one blended figure; this needs product sign-off since
   it changes how "the" weekly number reads to a founder with multi-currency
   revenue.

## Recommendation

Per `AGENTS.md`/`CLAUDE.md` ("record the ambiguity on the issue instead of
inventing product behavior," "stop and raise the conflict... do not quietly
widen scope"), this PR stays draft with this contract doc only until the
above are resolved on issue #26 — either with explicit answers or a decision
to split delivery/opt-out into a separate follow-up issue with its own ADR.
