# Recovery runbook: interrupted, retried, and replayed operational workflows

Operator-facing procedures for recognizing and safely recovering the resumable/idempotent
mechanisms that already exist in the system: Stripe backfill, Stripe webhook replay, attribution
recalculation, and leased scheduled deliveries. Nothing here invents new orchestration — every
procedure below drives production code and endpoints that already exist. See #81 for the drill
tests that prove the database-backed recovery mechanisms converge to a clean, non-duplicated state
after interruption or replay. Provider-facing delivery is called out separately where an external
side effect can be ambiguous.

**Before touching anything**: capture the current state (see each section's "before" check) so you
have a baseline to diff against after recovery. The database-backed billing and attribution
mechanisms below are designed to be safely retried. Do **not** generalize that guarantee to an
external provider call whose outcome is unknown: weekly-summary delivery has an explicit ambiguous-
outcome case that must be checked with the provider before replaying.

## Automatic background processing (#92)

Stripe webhook normalization and attribution recalculation now make automatic progress in a running
application instance -- an operator should not normally need to call any endpoint in the next two
sections for routine backlog drain. Two bounded, `@Scheduled` drivers exist:

- **`StripeWebhookNormalizationScheduler`** (`com.mrrorigin.billing`) ticks every
  `mrrorigin.billing.webhook-normalization.fixed-delay` (default `PT30S`) and calls the existing
  `StripeWebhookNormalizationService.processBatch` up to `max-batches-per-tick` times (default 10, at
  `batch-size` events each, default 50 -- so up to 500 events/tick). A backlog larger than that bound
  is finished by the next tick.
- **`AttributionRecalculationScheduler`** (`com.mrrorigin.attribution`) ticks every
  `mrrorigin.attribution.recalculation.scheduler.fixed-delay` (default `PT1M`), discovers up to
  `max-scopes-per-tick` (default 20) `(workspace, project)` scopes that are not yet `COMPLETED`
  (`AttributionRecalculationService#pendingScopes`), and calls the existing `runBatch` **exactly once**
  per scope, bounded to `max-customers-per-scope` (default 100). A scope that is already `COMPLETED` is
  excluded from discovery entirely, so this driver can never automatically restart a completed sweep --
  restart remains the explicit operator action documented below.

**Multi-replica / concurrency safety**: neither scheduler introduces any new locking. Both rely
entirely on the same DB-backed claim/lease (`StripeWebhookNormalizationService`'s
`SELECT ... FOR UPDATE SKIP LOCKED` + lease fencing) and row-lock (`AttributionRecalculationService`'s
`SELECT ... FOR UPDATE` on the run row) mechanisms this runbook already documents below -- two
application replicas, or two overlapping ticks in the same replica, safely divide work instead of
racing, exactly as if two operators had called the manual endpoints concurrently.

**Every endpoint documented in the rest of this runbook is unchanged and remains available.** The
scheduler is additive automation on top of the same mechanisms, not a replacement for them:

- Disable a misbehaving driver without a code change by setting
  `mrrorigin.billing.webhook-normalization.enabled` or
  `mrrorigin.attribution.recalculation.scheduler.enabled` to `false` (requires a redeploy/restart --
  there is no runtime toggle endpoint) and fall back to the manual `resume`/`replay-failed` calls below
  while investigating.
- A scope/backlog that isn't draining despite the scheduler running (check application logs for
  repeated tick failures first) is diagnosed and recovered exactly as described in the next two
  sections -- the scheduler calls the same `processBatch`/`runBatch` methods those sections already
  cover, so nothing about failure classification, replay eligibility, or restart semantics changes.
- The scheduler never calls `restart`. If a `COMPLETED` sweep needs to be redone (e.g. late
  `identify()` calls), that is still exclusively an explicit operator action via the `restart` endpoint
  documented below.

## Stripe backfill: interrupted or stuck initial sync

**How it works.** `stripe_connections.sync_checkpoint` stores `{phase, cursor}` as JSON
(`StripeBackfillCheckpoint`), advancing through `CUSTOMERS → PRICES → SUBSCRIPTIONS → INVOICES →
CHARGES → REFUNDS → DONE` (`StripeBackfillPhase`). Each page is applied and the checkpoint advanced
in one transaction (`StripeBackfillPageRunner`); a crash before commit leaves the checkpoint
untouched, so the same page is simply re-fetched and re-applied on the next call — never
double-applied, because every normalized table has a `UNIQUE (workspace_id, stripe_*_id)`
constraint and `BillingLedgerUpsertService` upserts by that key with a source-version guard that
never regresses newer data.

**Identify a stuck backfill.**

```
GET /api/workspaces/{workspaceId}/stripe-connection/health
```

Look at `backfillPhase` (not `DONE`) and `backfillComplete: false`. Combine with `lastSyncAt` /
`syncLagSeconds`: a phase that hasn't advanced across repeated health checks over an extended
window, with no corresponding Stripe-side rate-limit or outage, indicates a stuck (not just slow)
backfill — e.g. the connection lost eligibility mid-run (see `connectionStatus` /
`verificationStatus` in the same report) rather than a crashed process that will resume cleanly on
its own.

**Before recovering:** record the current `backfillPhase` and per-table row counts
(`billing_customers`, `billing_prices`, `billing_subscriptions`, `billing_invoices`,
`billing_payments`, `billing_refunds`, `billing_discounts`) for the workspace.

**Resume.**

```
POST /api/workspaces/{workspaceId}/stripe-connection/backfill/resume?maxPages=25
```

Manager permission required. Safe to call repeatedly and safe to call concurrently with itself —
concurrent calls converge without losing progress (`BillingLedgerConcurrencyAndIsolationIntegrationTests
#concurrentBackfillRunsForTheSameConnectionConvergeWithoutLosingProgress`). Call it in a loop
(bounded by `maxPages` per call, capped at 100) until the response's `complete: true`.

**After recovering:** re-check `/stripe-connection/health` — `backfillPhase` should read `DONE`,
`backfillComplete: true`, and per-table row counts should match what a clean single-pass backfill of
the same account would produce (there is no reason a resumed backfill should differ from an
uninterrupted one — see `StripeBackfillCheckpointIntegrationTests` and
`BillingLedgerConvergenceIntegrationTests` for the fixtures this invariant is tested against).

**Cross-tenant note:** the checkpoint, connection row lock, and every ledger table are
workspace-scoped. Resuming workspace A's backfill never reads, locks, or writes workspace B's rows —
this is asserted directly in `StripeBackfillCheckpointIntegrationTests`.

**When not to replay/resume:** if `connectionStatus` is not `ACTIVE` or `verificationStatus` is not
`VERIFIED`, resuming will fail closed with `409 Conflict` (`StripeBackfillIneligibleConnectionException`)
rather than silently doing nothing — fix the connection (reconnect/re-verify) first, don't retry the
resume endpoint in a loop expecting it to eventually succeed.

## Stripe webhook events: failed processing and replay

**How it works.** Every webhook delivery is stored durably before acknowledgment, keyed uniquely by
`(mode, stripe_event_id)` — a genuine duplicate delivery from Stripe can never create a second row.
Processing is claimed with a lease (`processing_state = SENDING`-style fencing in
`StripeWebhookNormalizationService`); an interrupted worker's lease expires and is safely reclaimed
by the next processing pass. `StripeWebhookReplayService` moves a `FAILED` event back to `PENDING`
for reprocessing; replaying an event that is not currently `FAILED` (already `PENDING` or
`PROCESSED`) is a documented no-op (`ReplayOutcome.NOT_ELIGIBLE`), not an error, and does not
increment `replay_count`.

**Identify failed events.**

```
GET /api/workspaces/{workspaceId}/stripe-webhook-events/failed?limit=50
```

Each entry reports `failureKind` (`TRANSIENT` vs `UNSUPPORTED`), `attemptCount`, `lastError`,
`replayCount`, and `lastReplayedAt`.

- `TRANSIENT` — a retriable condition (e.g. a dependent object fetch failed). Safe to replay once
  the underlying condition is fixed (e.g. Stripe outage resolved, rate limit cleared).
- `UNSUPPORTED` — the event's shape doesn't match anything the normalizer can process. Replaying
  without changing the input will fail identically every time; investigate the payload before
  replaying, don't loop the replay endpoint on it.

**Before replaying:** record row counts in whichever `billing_*` tables the event's `eventType`
affects (e.g. a `customer.subscription.updated` event affects `billing_subscriptions`,
`billing_subscription_items`, `billing_subscription_status_events`).

**Replay one event, or the whole failed backlog for a workspace:**

```
POST /api/workspaces/{workspaceId}/stripe-webhook-events/{eventId}/replay
POST /api/workspaces/{workspaceId}/stripe-webhook-events/replay-failed?maxEvents=25
```

Manager permission required. Both are safe to call concurrently with themselves — concurrent single-
event replay attempts replay exactly once, and concurrent batch replays partition the failed backlog
without overlap (`StripeWebhookReplayIntegrationTests`).

**After replaying:** re-check `/stripe-webhook-events/failed` — the event should no longer appear
(or should show a new, different failure if the underlying data is still bad). Compare the ledger
row counts you captured before replaying: a successful replay should produce the same row counts as
before if the event had already partially applied, or exactly the rows the event's payload implies
if it hadn't — never more rows than a single clean application would produce, because normalization
upserts by `(workspace_id, stripe_*_id)`, not insert-only.

**Known limitation (flagged, not fixed by this runbook):** normalized billing state produced by
webhook replay is not currently used to (re)compute `customer_mrr_movements` /
`customer_mrr_snapshots` — `RevenueCalculationService`, the only production entry point for that
calculation, is not invoked from webhook or backfill normalization today (discovered during #81;
tracked as its own piece of work in #83). If a replayed event's business data implies an MRR change,
that change will not appear in revenue reporting until #83 closes. Do not attempt to work around this by manually
invoking `RevenueCalculationService` outside of a reviewed code path — it requires a correctly
constructed `SubscriptionState`, and an ad hoc call is exactly how a real duplicate/incorrect
movement gets created.

**When not to replay:** if `lastError` shows a permanent data problem (e.g. the event references an
object your Stripe connection can no longer see, such as after the connected account itself changed
plans/regions), replaying repeatedly will not converge — escalate instead of looping the replay
endpoint.

## Attribution recalculation: interrupted or retried batch runs

**How it works.** `AttributionRecalculationService` checkpoints progress per `(workspace_id,
project_id, model_version)` in `attribution_recalculation_runs` (`status`, `cursor_customer_id`,
`customers_processed`), advancing the cursor and applying each customer's result in one transaction
per `runBatch` call. Results upsert on `(workspace_id, project_id, movement_id, model_version)`, so
retrying an already-applied batch reprocesses nothing (the cursor already moved past it) and a
genuine `restart()` reproduces byte-identical results because recalculation is deterministic and
upserts overwrite in place rather than duplicating
(`AttributionRecalculationServiceIntegrationTests`).

**#84 closed the operational gap this section used to flag.** `AttributionRecalculationController`
now exposes `status`/`runBatch`/`restart` over HTTP with no orchestration or semantics beyond what
`AttributionRecalculationService` already did — it is a thin, manager-authorized wrapper.

**Identify a stalled recalculation.**

```
GET /api/workspaces/{workspaceId}/projects/{projectId}/attribution-recalculation
```

Manager permission required (this is an operator surface, not a general read — unlike
`/attribution/coverage`, which only requires membership). A project id that does not belong to
`workspaceId` is rejected as `404`, same as a non-member workspace. Response:

```json
{
  "status": "RUNNING",
  "cursorCustomerId": "cus_123",
  "customersProcessed": 420,
  "complete": false,
  "modelVersion": "attribution-v1"
}
```

`status` is `NOT_STARTED` (with `cursorCustomerId: null`, `customersProcessed: 0`) if no run has ever
been created for this `(workspaceId, projectId)` — a deterministic response, not a `404`/`500`. Look
for `status: RUNNING` with no forward progress across repeated checks over an extended window (e.g. a
prior process crashed holding no lock, since the run row's lock is released with its transaction) to
distinguish a genuinely stuck run from one that is just slow.

**Before recovering:** capture the current `status`/`cursorCustomerId`/`customersProcessed` from the
response above and the row count in `customer_attribution_results` for the scope.

**Resume one bounded batch.**

```
POST /api/workspaces/{workspaceId}/projects/{projectId}/attribution-recalculation/resume?maxCustomers=100
```

Manager permission required. `maxCustomers` defaults to 100 and is bounded to `[1, 500]`; each call
processes at most that many customers in one transaction and durably advances the checkpoint — it
never loops internally, so bounded operational recovery means calling this endpoint, not expecting
one call to finish an arbitrarily large scope. Response reports the existing `BatchOutcome` shape plus
a derived `status`:

```json
{
  "customersProcessedThisBatch": 100,
  "totalCustomersProcessed": 520,
  "cursorCustomerId": "cus_987",
  "complete": false,
  "status": "RUNNING"
}
```

**Repeat resume until COMPLETE.**

```
while status != COMPLETED:
  POST .../attribution-recalculation/resume?maxCustomers=100
```

Safe to call repeatedly and safe to call concurrently with itself, same as backfill/replay above —
concurrent `resume` calls for the same `(workspaceId, projectId)` serialize on the run row and
converge without losing or duplicating progress
(`AttributionRecalculationServiceIntegrationTests#concurrentBatchRunsForTheSameScopeSerializeInsteadOfDuplicatingWork`).
Once a call's response reports `complete: true` (fewer than `maxCustomers` customers were processed),
the scope has been fully swept; further `resume` calls are safe no-ops until `restart`.

**Restart a completed sweep.**

```
POST /api/workspaces/{workspaceId}/projects/{projectId}/attribution-recalculation/restart
```

Manager permission required. Only valid from `COMPLETED` — e.g. because late `identify()` calls or
new links since completion mean previously "final" results should be revisited. Resets the checkpoint
to the beginning and moves the run back to `RUNNING`; a following `resume` performs a fresh sweep, and
because recalculation is deterministic and upserts overwrite in place, the results end up
byte-identical to the prior sweep if nothing about the underlying evidence changed.

**What "still running" means.** `restart` returns `409 Conflict` in two cases: the run is still
`RUNNING` (never call `restart` while another process might still be advancing the same
`(workspaceId, projectId, model_version)` — checkpoint and existing results are left untouched, not
corrupted), or no run has ever been created for this scope. Confirm via `status` first; if it reports
`RUNNING` with no forward progress across repeated checks, keep calling `resume` (it is always safe)
rather than looping `restart` expecting it to eventually succeed.

**After recovering:** row count in `customer_attribution_results` for the scope should match a
clean single-pass run over the same input data — recalculation never creates duplicate active
results for a customer under the same model version.

**Authorization:** every operation above (`status`, `resume`, `restart`) requires workspace manager
permission (`WorkspaceContext.requireManager`), which also blocks mutating calls once the workspace is
`DELETING` (`409 Conflict`). Non-member workspace access is concealed as `404`, matching the rest of
the API.

## Weekly summary deliveries: interrupted or stuck leases

**How it works.** `WeeklySummaryDeliveryRepository` claims a batch of due/failed/expired-lease rows
under `SELECT ... FOR UPDATE SKIP LOCKED`, setting `status = SENDING`, a fresh `lease_token`, and
`lease_until = now + 10 minutes`. Completion (`markSent`/`markFailed`/`markCancelled`) is fenced by
`WHERE lease_token = :leaseToken` — a worker whose lease already expired and was reclaimed by
another caller loses the fence and its own completion call becomes a silent no-op. An interrupted
lease (worker died mid-send) is not manually "unstuck": the next scheduled claim pass reclaims it
automatically once `lease_until` has passed. The natural key `(project_id, recipient_subject_id,
week_start)` prevents creation of a second **delivery row** for the same recipient/week. It does
**not** by itself prove an external email was sent exactly once: if the provider accepted the email
but the worker died before persisting `SENT`, the expired lease may later be reclaimed and a retry
could send the same email again. Lease fencing protects database state from stale workers; it cannot
atomically fence an already-completed provider side effect.

**Identify a stuck delivery.**

```
GET /api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary/deliveries?limit=50
```

Look for a delivery stuck in `SENDING` well past what 10 minutes plus retry backoff would explain,
or `PERMANENTLY_FAILED` / `BLOCKED_MISSING_EMAIL` entries (`attemptCount`, `lastError`,
`lastOutcomeAmbiguous`).

**Before replaying:** note the delivery's current `status`, `attemptCount`, and `providerMessageId`.
If `lastOutcomeAmbiguous: true`, treat that as a possible prior send even if local state does not
confirm success; check the provider before any manual replay.

**Recover:**

- A `SENDING` row past its lease becomes eligible for reclamation on the next scheduled claim pass;
  no manual database unlock is required. If the prior provider call may have escaped before the
  worker died, verify provider delivery state before deliberately forcing another send.
- A terminal failure can be manually replayed:

  ```
  POST /api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary/deliveries/{deliveryId}/replay
  ```

  `PERMANENTLY_FAILED` gets a fresh attempt budget; `BLOCKED_MISSING_EMAIL` is only replayed if the
  member now has a verified email.

**When not to replay:** if `lastOutcomeAmbiguous` is `true` (the provider call's outcome could not be
confirmed — it may have sent, or may not have), confirm with the email provider's own delivery logs
using `providerMessageId` before replaying, to avoid a real duplicate email to the founder. This is
the one recovery path in this document where exact-once external delivery is **not** guaranteed by
this system's database state; the operator must resolve the ambiguity using provider evidence.

## General checks before and after any recovery action

- **Before:** capture row counts for the tables the mechanism writes, and the mechanism's own
  checkpoint/status fields. Note whether other workspaces are actively processing the same kind of
  work concurrently (recovery is workspace/project-scoped and safe to run alongside unrelated
  workspaces, but you should still know what "before" looked like for the workspace you're touching).
- **After:** re-run the same health/status/listing endpoint and diff against the "before" snapshot.
  Row counts should reflect exactly the backlog that was actually outstanding — never more than a
  clean single run would have produced, and never less than what the failed/interrupted work was
  supposed to accomplish. For external delivery, also verify the provider outcome when local state
  is ambiguous; database row counts alone cannot establish exactly-once email delivery.
- **Escalate instead of retrying** when: the same replay/resume call fails identically three or more
  times with the same `lastError`/`failureKind`; the failure indicates a permanently invalid
  upstream object (Stripe-side data problem) rather than a transient condition; or a Postmark
  delivery is `lastOutcomeAmbiguous` and provider logs can't resolve it. Looping a replay/resume
  endpoint against a non-transient failure does not converge and just adds noise to `attemptCount`/
  `replayCount` history.
