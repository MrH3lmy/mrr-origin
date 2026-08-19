# ADR-0008: Workspace deletion lifecycle (#62)

- Status: Accepted
- Date: 2026-08-18

## Context

#27's accepted contract (recorded on the issue, child-issued as #62) requires an owner-only,
resumable, cross-module workspace deletion: mark the workspace `DELETING`, reject further writes,
revoke ingestion keys, disable Stripe sync, hard-delete every workspace-owned table across billing,
revenue, attribution, reporting, notification, tracking, identity, project, and membership data, retain
no Stripe invoice/event payload, and keep only a 30-day non-PII tombstone.

Nothing in the existing module graph can implement this without a new decision. ARCHITECTURE.md's
module table is a strict DAG (`workspace` depends on nothing; `notification` sits at the top, depending
on `reporting`) specifically so no module has to know about, or reach into, another module's
persistence internals. Workspace deletion is the first feature whose job is inherently cross-cutting:
one operation legitimately needs to drive billing, revenue, attribution, reporting, notification,
tracking, and identity all in the same run, in a dependency-safe order, plus the workspace's own
project/membership/root data. That does not fit inside any existing module without inverting its
dependency direction (e.g. `workspace` importing `billing` would break `workspace`'s "shared kernel
only" position).

The existing precedent for a resumable batch job is `ProjectDataDeletionService` (#8): a durable
phase/checkpoint row, read with `SELECT ... FOR UPDATE` before each bounded batch, advancing only once
a phase is exhausted. That pattern generalizes cleanly to a cross-module run; the open questions are
where the orchestration code lives, how per-module ownership is preserved, how "reject writes" is
enforced without a check in every controller, how the confirmation/idempotency/tombstone requirements
compose into one schema, and why `projects` and `workspace_members` need special handling.

## Decision

### A new module: `com.mrrorigin.workspacelifecycle`

This is the one module allowed to depend on every other module (`workspace`, `tracking`, `identity`,
`billing`, `revenue`, `attribution`, `reporting`, `notification`) — the same relationship the Spring
Boot application root already has to the whole module graph, scoped to one cross-cutting concern
instead of the whole application. It never touches another module's tables directly. Each module
instead exposes its own public `*WorkspaceDataDeletionService` (e.g. `BillingWorkspaceDataDeletionService`,
`TrackingWorkspaceDataDeletionService`) with one method, `deleteBatch(workspaceId, maxRows)`, that
hard-deletes up to `maxRows` rows from the first non-empty table it owns, in that module's own
dependency-safe internal order, and reports whether every owned table is now empty. This satisfies
#62's "module-owned deletion services — do not reach directly into another module's repositories" and
keeps every other module's architecture untouched: `ARCHITECTURE.md`'s module table gains one new row
(`workspacelifecycle`) rather than new edges on the other eight.

Each per-module service is stateless and idempotent across calls — it re-checks its owned tables from
scratch every time rather than keeping its own checkpoint — so the orchestrator's one persisted phase
per module is the only checkpoint needed anywhere in the system.

### Two tables: a run that cascades, and a tombstone that survives it

An earlier revision of this design used one table for both the in-flight checkpoint and the completed
tombstone. That does not hold up: a checkpoint row needs `phase`/`rows_deleted` operational columns
the tombstone contract does not call for, and once the workspace row is gone there is no way to
retroactively strip a row down to "request id, workspace UUID, status, timestamps" without a second
write anyway. V22 instead uses two tables with different lifetimes:

- `workspace_deletion_runs` — one row per workspace while a deletion is `RUNNING`, foreign-keyed to
  `workspaces(id) ON DELETE CASCADE`. `workspace_id` is database-unique (enforcing "one active deletion
  request per workspace"), and the row is read with `SELECT ... FOR UPDATE` before each batch exactly
  like `project_data_deletion_runs` — that row lock is the run's multi-instance-safe lease. Being
  foreign-keyed to `workspaces` means it needs no explicit cleanup: it cascades away in the same
  `DELETE FROM workspaces` statement that finishes the run.
- `workspace_deletion_tombstones` — written in that same transaction, immediately before the
  `workspaces` row is deleted. No foreign key (the row it would reference is gone by the time a later
  reader looks), and no columns beyond exactly the accepted contract: `id` (the same UUID as the run's
  `id`, so "request ID" carries through), `workspace_id`, `status`, `requested_at`, `completed_at`. It
  cannot leak PII because it was never given a column capable of holding any — not a redaction step
  applied to a row that used to hold more.

**Retry and status correctness after completion.** Because the run row is gone once a deletion
completes, `WorkspaceDeletionRequestService#status` and `#createOrGetRequest` both fall back to
`workspace_deletion_tombstones` whenever no run row is found, before concluding "never requested" or
"safe to start a new run." Getting this fallback right matters more than it looks: an earlier version
of this design checked only the run table, so a status query issued after completion (or, worse, a
service-level retry from a caller that does not go through `WorkspaceContext` authorization) would see
an empty result and either 404 or attempt to start a second, pointless run against a workspace whose
data is already gone. The fix is symmetric with how the run row disappears: `createOrGetRequest` checks
the tombstone table first, before ever touching `workspace_deletion_runs`, so a retry against a
completed deletion is a clean `COMPLETED` no-op rather than a reachable second run.

A daily `@Scheduled` sweep (`WorkspaceTombstonePurgeService`, mirroring
`WeeklySummaryDispatchService#retentionTick`'s cutoff-based cleanup) deletes tombstone rows completed
more than 30 days ago.

### Phase order: leaf-first, matching the module dependency graph

`ADMISSION → NOTIFICATION → REPORTING → ATTRIBUTION → REVENUE → IDENTITY → TRACKING → BILLING →
WORKSPACE_ROOT → DONE`. Declared in leaf-first order against `ARCHITECTURE.md`'s module table — the
most-dependent module runs first, exactly the ordering principle each per-module service's own internal
table order already applies one level down:

- `notification` depends on `reporting` → `NOTIFICATION` before `REPORTING`.
- `reporting` depends on `attribution`, `revenue` → `REPORTING` before `ATTRIBUTION`/`REVENUE`.
- `attribution` depends on `tracking`, `identity`, `revenue` → `ATTRIBUTION` before all three.
- `revenue` depends on `billing` → `REVENUE` before `BILLING`. `billing` and `revenue` have no foreign
  key between them at all (V7's cross-object references are plain Stripe ID columns by design), so this
  ordering is settled purely by the module graph, not by a constraint the database would otherwise
  enforce — but it is the direction a future foreign key between them would need to already be safe for.
- `identity` depends on `workspace`, `tracking` → `IDENTITY` before `TRACKING`.
- `tracking` and `billing` depend only on `workspace`, the base of the graph, so they run last among the
  table-sweep phases (either order is safe between them; `TRACKING` is declared first because `IDENTITY`
  — which runs just before it — already established a real foreign-key reason to be near it: see below).

This order is also dependency-safe against the real cross-module foreign-key graph, which is strictly
narrower than the module graph (most module pairs have no foreign key between their tables at all):
`ATTRIBUTION` (`customer_attribution_results`) runs before `IDENTITY` and `TRACKING` because V10
restricts deleting touchpoints and Stripe customer links while an attribution result references them;
`IDENTITY` runs before `TRACKING` because V6 restricts deleting a visitor while its alias references it,
and before `BILLING` because V8 restricts deleting a billing customer while a Stripe customer link
references it; `NOTIFICATION` runs before `WORKSPACE_ROOT` because V20 cascades
`weekly_summary_opt_outs` from `workspace_members`, which `WORKSPACE_ROOT` removes.
`AttributionWorkspaceDataDeletionService` also clears `attribution_recalculation_runs` (V11) alongside
`customer_attribution_results` — easy to miss, since it is operational recalculation bookkeeping rather
than a derived result, but it is workspace-owned data with no exemption in the accepted contract.

### Admission stops writes without a check in every controller

`ADMISSION` is itself a phase (not a side effect of request creation) so a crash mid-admission is
resumed like any other phase, but it always runs synchronously inside the very first
`createOrGetRequest` call rather than waiting for a separate `/run`, per the contract's "mark the
workspace DELETING first" ordering. It does three idempotent things:

1. `UPDATE workspaces SET status = 'DELETING' WHERE status = 'ACTIVE'`. `WorkspaceContext.requireManager`
   — the single authorization choke point #62's issue body calls for ("a WorkspaceContext-level check,
   not one check per controller") — now also rejects with 409 once the workspace is not `ACTIVE`. Every
   other module's authenticated write endpoints already route through `requireManager`, so this one
   change covers them uniformly. A new, stricter `requireOwner` is added alongside it for the deletion
   flow itself; unlike `requireManager`, `requireOwner` is deliberately **not** gated on deletion status,
   because the deletion flow is the one caller that must keep writing while the workspace is `DELETING`.
2. `IngestionKeyService.revokeAllForWorkspace` revokes every active key across every project in one
   statement. Public ingestion's existing `resolve()` already only matches non-revoked keys, so this
   alone stops public-ingestion writes with no new check in the `tracking` module's ingestion path.
3. `StripeConnectionService.disableSyncForDeletion` marks the connection `DISCONNECTED` locally, without
   calling Stripe's live deauthorize endpoint. `StripeBackfillPageRunner` already refuses to apply a page
   when `connection.status() != ACTIVE`, checked under the same row lock it uses for its own checkpoint —
   so this alone stops the Stripe backfill job, and newly arriving webhooks stop resolving to a live
   connection, with no new check in the `billing` module. A durable, resumable, idempotent-under-retry
   admission step deliberately does not depend on an external network call succeeding; revoking Stripe's
   own OAuth grant is a separate, non-blocking concern out of scope here.

### Full write-path inventory

`requireManager` closes most authenticated writes in one place, but not all of them, and not the two
kinds of writes that never go through `WorkspaceContext` at all. Every `@PostMapping`/`@PutMapping`/
`@PatchMapping`/`@DeleteMapping` in `apps/api` was enumerated and checked against `DELETING`:

| Path                                                                                                                                                                                                                                                | Gate                                 | Status                                                                                                                                                                                                                                                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Every `requireManager`-gated controller/service write (projects, members, ingestion keys, allowed domains, retention settings, Stripe connect/disconnect, Stripe customer link/repair, webhook replay, backfill resume, weekly-summary send/replay) | `requireManager`                     | Covered — 409 once `DELETING`                                                                                                                                                                                                                                                       |
| Public event ingestion (`EventIngestionController`)                                                                                                                                                                                                 | Ingestion-key revocation             | Covered — keys revoked in `ADMISSION`, `resolve()` only matches non-revoked keys                                                                                                                                                                                                    |
| Stripe webhook normalization (durable receipt itself is intentionally never blocked, per `ARCHITECTURE.md`'s "webhooks acknowledged only after durable receipt")                                                                                    | Stripe connection status             | Covered — `ADMISSION` disables sync, `StripeBackfillPageRunner`/normalization already gate on `connection.status() == ACTIVE`                                                                                                                                                       |
| Weekly-summary dispatch tick creating new delivery rows                                                                                                                                                                                             | `ProjectRepository` query filter     | Covered — `NOT EXISTS (... w.status = DELETING)`                                                                                                                                                                                                                                    |
| Weekly-summary dispatch tick **sending already-queued** delivery rows (`WeeklySummaryDeliveryRepository#claim`)                                                                                                                                     | _(none, until this fix)_             | **Fixed** — a `PENDING`/`FAILED` row queued before `DELETING` would otherwise still be claimed and actually emailed by the next tick, racing the `NOTIFICATION` phase that is about to hard-delete it. `claim`'s claimable-row CTE now excludes rows whose workspace is `DELETING`. |
| `TrackingVerificationController#start` (self-service, `requireMembership` only — starting an install-verification attempt needs no manager role)                                                                                                    | _(none, until this fix)_             | **Fixed** — added an explicit `WorkspaceContext.requireWritable` call alongside `requireMembership`                                                                                                                                                                                 |
| `WeeklySummaryOptOutController#updateOptOut` (self-service, a member's own opt-out)                                                                                                                                                                 | _(none, until this fix)_             | **Fixed** — same `requireWritable` addition                                                                                                                                                                                                                                         |
| `WorkspaceDeletionController#create`/`#run` (the deletion flow's own writes)                                                                                                                                                                        | `requireOwner`, deliberately ungated | Exempt by design — see above                                                                                                                                                                                                                                                        |

The last two needed a new `WorkspaceContext.requireWritable(workspaceId)` method: a self-service write
gated only by `requireMembership` (not `requireManager`, since it needs no manager role) has no existing
choke point to inherit the `DELETING` check from. `requireWritable` is the same `requireNotDeleting`
check `requireManager` already runs internally, exposed for a caller that already has membership but
needs the write gate without the role gate. This is the concrete instance of a broader rule: any new
write path that is neither `requireManager`-gated, ingestion-key-gated, nor Stripe-connection-status-
gated needs one of these two treatments explicitly — there is no third mechanism that catches it for
free.

### `projects` and `workspace_members` are never their own phase

Both cascade from `workspaces` (`ON DELETE CASCADE`), and by `WORKSPACE_ROOT` nothing else restricts
deleting them — every earlier phase has already cleared the tables that do (`external_identities`,
`tracking_ingestion_batches`, `stripe_customer_links`, ...). Sweeping them as their own authorized phase
would require the same owner check `WORKSPACE_ROOT` performs, except a membership row deleted by an
earlier call would make every later call in the same run unable to re-authenticate — the owner would be
locked out of finishing their own deletion. Instead, `WORKSPACE_ROOT` performs one `DELETE FROM
workspaces WHERE id = :id`, authorized once at the start of that call, letting Postgres cascade both
tables in the same atomic statement.

### No retained Stripe payloads

`BillingWorkspaceDataDeletionService` actively deletes `stripe_webhook_events` rather than leaving it to
its `ON DELETE SET NULL` orphaning when `stripe_connections` is removed — the accepted contract is
explicit that no Stripe invoice/event payload is retained; Stripe remains the system of record.

## Consequences

- `ARCHITECTURE.md`'s module table gains a ninth module, `workspacelifecycle`, documented as the one
  module allowed to depend on all others — a deliberate, narrow exception to the otherwise-strict DAG,
  scoped to this one cross-cutting lifecycle/security-boundary concern.
- `WorkspaceContext.requireManager` now has a side effect (a 409 once `DELETING`) that every existing
  caller inherits automatically; no other module needed to change to get workspace-wide write rejection.
- A write path that is neither `requireManager`-gated, ingestion-key-gated, nor Stripe-connection-
  status-gated — a self-service `requireMembership`-only write, or a scheduled job's own claim/candidate
  query — needs an explicit `DELETING` check added by hand (`WorkspaceContext.requireWritable`, or a SQL
  filter matching `WeeklySummaryDeliveryRepository#claim`'s). This is a pattern to repeat for each new
  case, not a guarantee the platform enforces automatically; the full inventory above is the checklist
  a future write path should be checked against.
- Deleting a workspace is irreversible from the moment `ADMISSION` runs: there is no API to move a
  workspace back to `ACTIVE`, matching the product decision that this is a one-way action gated by an
  explicit typed confirmation.
