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

### One schema table plays checkpoint and tombstone

`workspace_deletion_requests` (V22) is a single lifetime row per workspace, `workspace_id` database-
unique (enforcing "one active deletion request per workspace" — a retry or a second confirmed request
finds the same row instead of starting duplicate work). While `RUNNING` it is the run's
phase/row-count checkpoint, locked with `SELECT ... FOR UPDATE` exactly like `project_data_deletion_runs`
— that row lock is the run's multi-instance-safe lease, since a Postgres row lock is visible to every
API instance sharing the database. Once `COMPLETED`, the same row already contains nothing but the
tombstone contract's four fields (request id, workspace UUID, status, timestamps): the confirmation
string is validated by the controller and never persisted, and no requester subject id is stored
anywhere, so the row is a compliant tombstone by construction rather than by a separate redaction step.
A daily `@Scheduled` sweep (`WorkspaceTombstonePurgeService`, mirroring
`WeeklySummaryDispatchService#retentionTick`'s cutoff-based cleanup) deletes rows completed more than 30
days ago. The table has no foreign key to `workspaces(id)`, since it must outlive the workspace row,
which is hard-deleted as the run's final phase.

### Phase order

`ADMISSION → REPORTING → ATTRIBUTION → IDENTITY → TRACKING → BILLING → REVENUE → NOTIFICATION →
WORKSPACE_ROOT → DONE`. This is dependency-safe against the full cross-module foreign-key graph:
`ATTRIBUTION` (`customer_attribution_results`) runs before `IDENTITY` and `TRACKING` because V10
restricts deleting touchpoints and Stripe customer links while an attribution result references them;
`IDENTITY` runs before `TRACKING` because V6/V8 restrict deleting visitors/billing customers while a
visitor alias or Stripe customer link references them; `NOTIFICATION` runs before `WORKSPACE_ROOT`
because V20 cascades `weekly_summary_opt_outs` from `workspace_members`, which `WORKSPACE_ROOT` removes.
`BILLING` and `REVENUE` have no foreign keys between them or to any earlier-swept table (V7's
cross-object references are plain Stripe ID columns by design), so their relative order does not matter.

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

The one gap this does not close: a scheduled job with no per-request `WorkspaceContext` and no existing
status-based gate. `WeeklySummaryDispatchService`'s project-candidate query (`ProjectRepository`) is
given an explicit `NOT EXISTS (... w.status = DELETING)` filter, closing that one case directly rather
than inventing a general mechanism for a single caller.

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
- A scheduled job that is added later and writes tenant-owned data on its own trigger (not behind
  `requireManager`, an ingestion key, or a Stripe connection) needs its own explicit `DELETING` filter,
  the same way `WeeklySummaryDispatchService`'s candidate query does — this is a pattern to repeat, not
  a guarantee the platform enforces automatically for every future scheduled job.
- Deleting a workspace is irreversible from the moment `ADMISSION` runs: there is no API to move a
  workspace back to `ACTIVE`, matching the product decision that this is a one-way action gated by an
  explicit typed confirmation.
