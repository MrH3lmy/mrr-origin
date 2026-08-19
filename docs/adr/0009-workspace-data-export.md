# ADR-0009: Workspace data export (#64)

- Status: Accepted
- Date: 2026-08-19

## Context

#27's accepted contract (child issue #64) requires a manager-only, synchronously streamed ZIP
export of everything MRROrigin holds for a workspace: a versioned `manifest.json` plus one
streaming NDJSON file per workspace-owned domain (billing, revenue, attribution,
reporting/read-models, notification, tracking), excluding credentials, secret digests,
ingestion-key secrets, Stripe/webhook secrets, lease tokens, checkpoint tokens, and other
internal security material, audited on success the same way `ExportAuditService` audits the
existing CSV exports.

This is the same shape of problem ADR-0008 already solved for workspace deletion: one operation
that legitimately needs to read across billing, revenue, attribution, reporting, notification, and
tracking in a single run, which does not fit inside any single module in `ARCHITECTURE.md`'s
strict DAG without inverting a dependency (e.g. `billing` importing `reporting`). Unlike deletion,
export does not need `identity` (the accepted contract's domain list omits it — attribution's own
exported rows already carry the acquisition evidence a workspace needs) and does not touch
`workspace`-root data (members/projects) at all, so its cross-cutting surface is narrower than
`workspacelifecycle`'s.

The existing streaming precedent is `CsvExportController`/`CustomersCsvExportService` (#26):
never buffer a full export into memory, and for a result set not bounded by a small dimension
cardinality, walk a bounded keyset-paginated cursor page by page rather than fetching everything
into one list first.

## Decision

### A new module: `com.mrrorigin.workspaceexport`

Per the same reasoning as ADR-0008, this is a second module allowed to depend on other modules
below it — narrower than `workspacelifecycle`'s "every other module" grant: only `workspace`,
`billing`, `revenue`, `attribution`, `reporting`, `notification`, and `tracking` (no `identity`,
matching the accepted contract's domain list). It never touches another module's tables directly.
Each of the six domain modules instead exposes its own public `*WorkspaceExportService`
(`BillingWorkspaceExportService`, `RevenueWorkspaceExportService`,
`AttributionWorkspaceExportService`, `ReportingWorkspaceExportService`,
`NotificationWorkspaceExportService`, `TrackingWorkspaceExportService`) with one method,
`writeNdjson(workspaceId, Writer)`, that streams every row this module owns for that workspace as
one JSON object per line and returns the row count written. This satisfies #64's "module-owned
export/read services — do not reach directly across modules into another module's repositories"
and keeps every other module's architecture untouched: `ARCHITECTURE.md`'s module table gains one
new row (`workspaceexport`) rather than new edges on the six domain modules.

Each per-module export service is read-only and stateless: like the `*WorkspaceDataDeletionService`
siblings it does not share a common Java interface (there is no polymorphic dispatch — the
orchestrator calls each module's concrete service directly, matching
`WorkspaceDeletionRequestService`'s own `switch` over per-module services). Unlike those siblings'
one-line `deleteBounded` helper, the keyset-pagination-plus-NDJSON-line-writing mechanics here are
non-trivial and correctness-sensitive enough that duplicating them across all six services risks a
silent bug in one copy (e.g. an off-by-one in cursor advancement that stops paginating early,
silently truncating an export). `WorkspaceExportStreaming` (in `com.mrrorigin.workspace`) is a small,
purely technical helper with no business logic — bounded keyset pagination plus NDJSON line writing —
shared by all six. It adds no new module-graph edge: every domain module already depends on
`workspace` as its shared-kernel base per `ARCHITECTURE.md`'s existing table. Each
`*WorkspaceExportService` still owns its own table list, explicit column allow-list, and row mapping
(what to export and how to shape it); the shared helper only owns how to page through rows without
buffering them.

### Streaming a ZIP without buffering the export or delaying the manifest

The accepted contract requires `manifest.json` to record each NDJSON file's row count, but the row
counts are only known once each file has been fully streamed. Buffering an NDJSON file's content
until its count is known would violate the "never buffer a full export into memory" requirement, so
instead `WorkspaceDataExportService` writes the six NDJSON files as ZIP entries first, in a fixed
order, accumulating only a `long` row count per file (not their content) as each row is streamed
through `ZipOutputStream`, then writes `manifest.json` as the final entry once every count is known.
This keeps memory bounded to a handful of counters and one page (small, fixed page size) of rows at
a time per table, never the full export — the same shape `CustomersCsvExportService` already
established, generalized from CSV rows to NDJSON rows and from one file to a ZIP of files. A ZIP
reader does not require `manifest.json` to be the first physical entry in the archive (central
directory lookup finds it by name), so writing it last does not violate "manifest.json at the ZIP
root" — root here means path depth (no directory prefix), not physical entry order.

### Row counts audited, not exported content

`WorkspaceExportAuditService` records to a new `workspace_export_audit_log` table (a new migration,
not a reuse of `export_audit_log`): actor subject, schema version, per-domain row counts, and total
row count, after the ZIP write completes successfully. The existing `export_audit_log` (V18) has a
`NOT NULL project_id` and a `CHECK export_type IN ('COMPARISON', 'RETENTION_COHORTS', 'CUSTOMERS')`
— both wrong for a workspace-wide, non-project-scoped, six-domain export, and neither is something
this issue should widen (that table is #26's CSV-export-specific audit trail). A new table keeps the
same "append-only, actor + counts, never the exported row content itself" pattern without stretching
an existing table's contract.

### Explicit column allow-lists, not `SELECT *`

Every per-module export query names its columns explicitly rather than selecting every column, so a
future column added to an owned table is excluded by default rather than silently exported. The
concrete exclusions applied against `docs/security/threat-model.md`'s export guidance and #64's
named categories:

| Category (from #64)                               | Concrete column(s) excluded                                                                                                                                                                                                                |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Credentials                                       | `tracking_verification_attempts.token` (single-use bearer credential)                                                                                                                                                                      |
| Secret digests                                    | `project_ingestion_keys.secret_hash`; `stripe_oauth_states` (entire table — CSRF state hash, ephemeral security bookkeeping, not workspace business data)                                                                                  |
| Ingestion-key secrets                             | `project_ingestion_keys.secret_hash`                                                                                                                                                                                                       |
| Stripe/webhook secrets                            | Never stored in the database at all (ADR-0003: platform key and webhook signing secrets are process environment configuration, never a per-workspace row) — `stripe_oauth_states` excluded regardless, as ephemeral OAuth CSRF bookkeeping |
| Lease tokens                                      | `weekly_summary_deliveries.lease_token` / `.lease_until`                                                                                                                                                                                   |
| Checkpoint tokens                                 | `stripe_connections.sync_checkpoint`                                                                                                                                                                                                       |
| (raw verification artifact, not a named category) | `stripe_webhook_events.raw_payload` — the exact signed bytes kept only for cryptographic signature verification; the same row's parsed `payload` column is included and carries the business data                                          |

**Revised on review (#78):** an earlier revision of this ADR excluded `stripe_webhook_events`
entirely from `billing.ndjson`, reasoning it was "operational ingestion bookkeeping, not the
normalized billing ledger." Review on #78 correctly rejected that: #64's accepted contract is a
complete export of everything MRROrigin holds for the workspace, and "raw ingestion/replay data" is
not itself security material — it does not belong on the same list as a signing secret, an
OAuth-state hash, or a checkpoint token, and excluding a whole table for that reason is a real
narrowing of the contract, not a scoping detail. `stripe_webhook_events` is now included in
`billing.ndjson` with an explicit column allow-list, excluding only `raw_payload` (the table's one
genuinely security/verification-only column — see the table above) while keeping the parsed
`payload` JSONB column, `processing_state`, and every other business/operational column.

## Consequences

- `ARCHITECTURE.md`'s module table gains a tenth module, `workspaceexport`, documented as a second
  deliberate, narrow exception to the otherwise-strict DAG, scoped to this one cross-cutting
  read/export concern — distinct from `workspacelifecycle`'s write/delete concern, with a narrower
  dependency set (no `identity`, no `workspace`-root cascade).
- A future new table owned by any of the six domain modules is invisible to the export until a
  developer explicitly adds it to that module's `*WorkspaceExportService` allow-list — the same
  trade-off `*WorkspaceDataDeletionService`'s explicit `TABLES_IN_ORDER` lists already make for
  deletion, and the reason the exclusion regression tests assert against literal forbidden values
  rather than only against a table/column list.
- `stripe_webhook_events.raw_payload` (the raw signed bytes) remains excluded; a future need to
  re-verify or replay the exact original webhook signature from an export would need a dedicated,
  explicitly-scoped follow-up, not a default inclusion.
