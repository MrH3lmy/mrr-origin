# Observability runbook: metrics, alerts, and dashboard (P6, #28/#90)

This is the operator-facing reference for the metrics contract added in #90 (the observability slice
of #28). It documents every custom meter this application exposes, how each checked-in alert
(`docs/observability/alerts.yml`) consumes those meters, and how to recover once an alert fires.
Recovery _procedures_ live in `docs/operations/recovery-runbook.md`; this document links to them
rather than duplicating them.

**Scope statement:** the repository provides metric exposition, alert rules, and a dashboard
definition. Deployment of Prometheus/Grafana or another Prometheus-compatible backend is outside
application scope -- nothing in this repository provisions, runs, or assumes a running monitoring
stack.

## Review fixes (PR #91)

Four blocking findings from review, all addressed in this PR:

1. **Transactional success metrics surviving rollback.** `EventIngestionService.ingest()`,
   `StripeWebhookIngestionService.ingest()`, `StripeWebhookReplayService`, `RevenueCalculationService`,
   and `AttributionRecalculationService.runBatch` are all `@Transactional`; a Micrometer increment
   made before the method returns is not itself part of that DB transaction. A later step in the same
   call throwing (e.g. a mid-batch identity/session conflict) rolled back every DB write but left an
   earlier "accepted"/"stored"/"supported" increment standing. Fixed by deferring every such increment
   to `TransactionSynchronizationManager.registerSynchronization(...).afterCommit()` -- it fires only
   if the transaction actually commits; a rollback simply never triggers it. A confirmed-failure
   counter (e.g. `..._failure`, `..._failures`) is the one exception: it is recorded immediately,
   because the exception/rollback _is_ the definitive signal for that metric, with nothing to wait
   for. Regression test:
   `IngestionMetricsIntegrationTests#aRolledBackBatchDoesNotLeaveAPhantomAcceptedIncrement`.
2. **Revenue result counters inflated by full historical replay.** `RevenueCalculationService.replay()`
   rebuilds a customer's entire historical snapshot series on every `recordAndReplay` call. A counter
   incremented per snapshot inside `saveSnapshot`/`saveUnsupported` therefore re-counted old,
   unrelated historical instants every time an unrelated later state for the same customer forced a
   fresh replay -- unusable for an alert claiming a genuinely new unsupported pattern appeared. Fixed
   by replacing those counters with (a) `mrrorigin.revenue.calculation.invocations{result}`, an
   invocation-level counter incremented exactly once per `recordAndReplay` call, and (b) DB-backed
   gauges (`RevenueCalculationSnapshotMetrics`) reporting the _current_ persisted
   supported/unsupported snapshot counts, which are immune to replay-count inflation because a gauge
   reports what's true now, not how many times it was recomputed. Regression test:
   `RevenueCalculationMetricsIntegrationTests#unrelatedLaterStateTriggeringFullReplayDoesNotRecountEarlierHistoricalOutcomes`.
3. **Ingestion rejection-rate alert mixed units.** `mrrorigin_ingestion_rejected_total` is a
   per-request counter; `mrrorigin_ingestion_events_total` is a per-event counter. Dividing one by
   their sum understated the true request-level rejection rate for any multi-event batch. Fixed by
   computing `PublicIngestionRejectionRateHigh`/`...Critical` entirely from
   `http_server_requests_seconds_count{uri="/api/public/v1/events",...}` (request-level on both
   sides). `mrrorigin.ingestion.events` remains an event-throughput metric, unchanged.
4. **Several alert rules could fire from one isolated failure.** `rate(metric[15m]) > 0` combined
   with a `for:` duration shorter than the rate window is satisfied by a single event sitting inside
   that window -- indistinguishable from a repeated failure. Fixed by switching every such rule to
   `increase(metric[15m]) >= N` for a real `N > 1` (`PublicIngestionServerErrorRateHigh`,
   `ReportingErrorRateHigh`, `MrrCalculationFailing`, `AttributionRecalculationFailing`) or adding an
   explicit minimum-traffic floor to existing ratio alerts
   (`PublicIngestionRejectionRateHigh`/`...Critical`, `StripeWebhookFailureRateHigh`,
   `ReportingLatencyDegraded`). Also reconciled the ingestion-rejection tag vocabulary: it is now
   genuinely exhaustive and pre-registered (`invalid_key`, `blocked_origin`, `invalid_payload`,
   `conflict` -- `timestamp_out_of_range` was missing from the mapping and would have fallen through
   to an unregistered `other` value); an unmapped `EventIngestionException` code now fails loudly
   (`IllegalStateException`) instead of silently emitting a fifth, never-pre-registered tag value.

## Scraper expectations

- Endpoint: `GET /actuator/prometheus`, Prometheus text exposition format (via
  `micrometer-registry-prometheus`).
- **No authentication is presented by the scraper and none is required by the endpoint** -- it is
  `permitAll` in `SecurityConfiguration`, the same posture as `/actuator/health*`/`/actuator/info`,
  because a Prometheus-compatible scraper cannot present a user JWT and this system has no
  operator/service-account identity to issue one to (`docs/security/threat-model.md` §4). **The
  access-control boundary for this endpoint in any real deployment is network-level isolation**:
  the scrape target must not be reachable from the public internet. This is a deployment
  responsibility, not something enforced in application code -- track it on the private-beta
  infrastructure checklist alongside the existing infra/DB access-restriction requirement
  (`docs/security/threat-model.md` §4).
- No other Actuator endpoint is exposed beyond `health`, `info`, and `prometheus`
  (`management.endpoints.web.exposure.include`). `env`, `beans`, `heapdump`, `httptrace`, etc. remain
  unavailable, unchanged from before this slice.
- Every custom meter is free of workspace/project/customer/Stripe-object/email/JWT-subject
  identifiers in its tags -- scraping this endpoint is safe from any network position that can reach
  it, but "safe from a privacy/business-value standpoint" is not the same as "should be public";
  operational metrics (traffic volume, error rates, backlog sizes) are still information an operator
  would not want exposed to arbitrary internet clients.
- Recommended scrape interval: 30-60s. All gauges here are computed live, on scrape, directly from the
  database (see each gauge's "source" below) -- there is no separate polling/caching job to configure,
  and scraping more frequently than the underlying data changes provides no additional signal.

## SLI catalog

| Metric                                                         | Type                             | Tags                                                             | Source                                                                                                         | Meaning                                                                                                                                                                                                                                                                 |
| -------------------------------------------------------------- | -------------------------------- | ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mrrorigin.ingestion.events`                                   | Counter                          | `result=accepted\|duplicate`                                     | `EventIngestionService`                                                                                        | Per-event outcome of public tracking ingestion.                                                                                                                                                                                                                         |
| `mrrorigin.ingestion.rejected`                                 | Counter                          | `reason=invalid_key\|blocked_origin\|invalid_payload\|conflict`  | `EventIngestionController`                                                                                     | Per-request rejection before/instead of any event being accepted.                                                                                                                                                                                                       |
| `http.server.requests{uri="/api/public/v1/events"}`            | Timer (Spring auto-instrumented) | `method,status,outcome`                                          | Spring MVC                                                                                                     | Ingestion request latency and status-code distribution, including `429` rate-limit rejections (`IngestionRateLimitInterceptor`) -- not duplicated by a custom meter.                                                                                                    |
| `mrrorigin.stripe.webhook.received`                            | Counter                          | `mode=test\|live`, `outcome=stored\|duplicate\|orphaned`         | `StripeWebhookIngestionService`                                                                                | Raw Stripe webhook deliveries after signature verification, before normalization.                                                                                                                                                                                       |
| `mrrorigin.stripe.webhook.processed`                           | Counter                          | `result=processed\|skipped`                                      | `StripeWebhookNormalizationService`                                                                            | Outcome of a normalization attempt for a claimed event.                                                                                                                                                                                                                 |
| `mrrorigin.stripe.webhook.failed`                              | Counter                          | `failure_kind=transient\|unsupported`                            | `StripeWebhookNormalizationService`                                                                            | Normalization failures, classified per `StripeWebhookFailureKind`.                                                                                                                                                                                                      |
| `mrrorigin.stripe.webhook.replay`                              | Counter                          | (none)                                                           | `StripeWebhookReplayService`                                                                                   | Count of FAILED events successfully requeued to PENDING (single or batch replay).                                                                                                                                                                                       |
| `mrrorigin.stripe.webhook.pending`                             | Gauge                            | `mode`                                                           | `StripeWebhookQueueMetrics` (live query: `COUNT(*) WHERE processing_state='PENDING'`)                          | Current unprocessed webhook backlog, aggregated across every workspace.                                                                                                                                                                                                 |
| `mrrorigin.stripe.webhook.oldest_pending_age_seconds`          | Gauge                            | `mode`                                                           | `StripeWebhookQueueMetrics` (live query: `MIN(received_at)` of PENDING rows)                                   | Age of the oldest unprocessed event -- the real stall signal even when the backlog count itself looks small.                                                                                                                                                            |
| `mrrorigin.stripe.backfill.incomplete`                         | Gauge                            | `mode`                                                           | `StripeBackfillProgressMetrics` (live query over `stripe_connections`)                                         | Count of ACTIVE/VERIFIED connections whose backfill checkpoint has not reached `DONE`.                                                                                                                                                                                  |
| `mrrorigin.stripe.backfill.stalled_age_seconds`                | Gauge                            | `mode`                                                           | `StripeBackfillProgressMetrics`                                                                                | Age since the least-recently-advanced incomplete connection's checkpoint last moved (`stripe_connections.updated_at`, stamped exactly on checkpoint advance by `StripeBackfillPageRunner`).                                                                             |
| `mrrorigin.revenue.calculation.invocations`                    | Counter                          | `result=success\|failure`                                        | `RevenueCalculationService`                                                                                    | One increment per `recordAndReplay` call (deferred to after-commit for `success`; immediate for `failure`, since a thrown exception is itself the confirmed-rollback signal) -- never once per historical snapshot `replay()` happens to touch (review fix: see below). |
| `mrrorigin.revenue.calculation.supported_snapshots`            | Gauge                            | (none)                                                           | `RevenueCalculationSnapshotMetrics` (live query: `COUNT(*) WHERE supported=true`)                              | Current persisted count of supported MRR snapshots, aggregated across every workspace/customer.                                                                                                                                                                         |
| `mrrorigin.revenue.calculation.unsupported_snapshots`          | Gauge                            | `reason` (`UnsupportedReason` enum, lowercased)                  | `RevenueCalculationSnapshotMetrics` (live query: `COUNT(*) WHERE supported=false GROUP BY unsupported_reason`) | Current persisted count of unsupported MRR snapshots, broken down by reason, aggregated across every workspace/customer.                                                                                                                                                |
| `mrrorigin.revenue.calculation.duration`                       | Timer                            | (none)                                                           | `RevenueCalculationService`                                                                                    | Latency of one `recordAndReplay` call (one customer's full replay).                                                                                                                                                                                                     |
| `mrrorigin.attribution.recalculation.batches`                  | Counter                          | `outcome=completed\|in_progress`                                 | `AttributionRecalculationService`                                                                              | One increment per `runBatch` call, tagged by whether that call finished the scope's sweep.                                                                                                                                                                              |
| `mrrorigin.attribution.recalculation.customers_processed`      | Counter                          | (none)                                                           | `AttributionRecalculationService`                                                                              | Cumulative customers processed across every `runBatch` call.                                                                                                                                                                                                            |
| `mrrorigin.attribution.recalculation.failures`                 | Counter                          | (none)                                                           | `AttributionRecalculationService`                                                                              | `runBatch` invocations that threw.                                                                                                                                                                                                                                      |
| `mrrorigin.attribution.recalculation.batch.duration`           | Timer                            | (none)                                                           | `AttributionRecalculationService`                                                                              | Latency of one `runBatch` call.                                                                                                                                                                                                                                         |
| `mrrorigin.attribution.recalculation.running`                  | Gauge                            | (none)                                                           | `AttributionRecalculationQueueMetrics` (live query over `attribution_recalculation_runs`)                      | Count of runs currently `RUNNING`, across every workspace/project.                                                                                                                                                                                                      |
| `mrrorigin.attribution.recalculation.stale`                    | Gauge                            | (none)                                                           | `AttributionRecalculationQueueMetrics`                                                                         | Count of `RUNNING` runs whose `updated_at` is older than 1 hour (see threshold rationale below).                                                                                                                                                                        |
| `http.server.requests{uri=~"/api/workspaces/.*/reporting/.*"}` | Timer (Spring auto-instrumented) | `method,status,outcome`                                          | Spring MVC                                                                                                     | Reporting endpoint latency/error rate. Not duplicated by a custom timer -- the URI template tag is already bounded (no resolved workspace/project id).                                                                                                                  |
| `mrrorigin.notification.weekly_summary.deliveries`             | Gauge                            | `status` (`weekly_summary_deliveries.status` values, lowercased) | `WeeklySummaryDeliveryQueueMetrics` (live query)                                                               | Current delivery-row count per status, aggregated across every workspace/project.                                                                                                                                                                                       |
| `mrrorigin.notification.weekly_summary.stale_lease`            | Gauge                            | (none)                                                           | `WeeklySummaryDeliveryQueueMetrics`                                                                            | Count of `SENDING` rows whose lease has already expired (`lease_until <= now`) but not yet reclaimed by a tick.                                                                                                                                                         |

Every tag above is a small, fixed enum. **None of these metrics ever carries a workspace id, project
id, customer id, Stripe object id, email address, JWT subject, or any other client- or tenant-supplied
value** -- see "Cardinality rules" below.

### Deliberately not implemented: client-timestamp ingestion lag

The obvious "ingestion lag" SLI would be `received_at - occurredAt` (the client-supplied event
timestamp). This is **not implemented** because `occurredAt` is client-controlled and only weakly
bounded (`EventIngestionService#validateTimestamps` allows anywhere from 30 days in the past to 5
minutes in the future) -- a slow/buggy/malicious client can make this number arbitrarily large or
negative without any actual processing delay on this system's part. Trusting it as a precise SLI would
produce false alerts driven entirely by client behavior. **`http.server.requests` request-processing
latency is the substitute SLI** for "is ingestion keeping up," per the issue's own guidance to clamp or
document rather than trust arbitrary client timestamps.

### Deliberately not implemented: backfill failed/retried batch counts

`StripeBackfillService`/`StripeBackfillPageRunner` do not persist a durable record of a failed page
application anywhere in the schema -- a failure surfaces only as a thrown exception to the synchronous
HTTP caller of `POST .../backfill/resume`. Adding a metric here would require inventing new persistence
just to support it, which is out of scope for this slice. `mrrorigin.stripe.backfill.incomplete` and
`.stalled_age_seconds` are the grounded signals that already exist.

## Cardinality rules

- Tags are restricted to the bounded enums listed in the SLI catalog above (`result`, `outcome`,
  `reason`, `mode`, `status`, `failure_kind`). Every enum has a small, fixed number of values known at
  compile time.
- No metric is ever registered per-workspace, per-project, per-customer, or per-connection. Every
  backlog/staleness gauge queries and aggregates across every tenant in one number (or one number per
  `mode`/`status`), by design -- this is what makes it safe to expose without per-tenant business data
  leaking through cardinality alone.
- An automated test (`MeterRegistryCardinalityTests`, see below) asserts no custom `mrrorigin.*` meter
  carries a tag key outside an explicit allow-list (`result`, `outcome`, `reason`, `mode`, `status`,
  `failure_kind`), as a regression guard against a future change accidentally adding a tenant id tag.

## Alert catalog

Full PromQL lives in `docs/observability/alerts.yml`. Each entry below: what it means, the first thing
an operator checks, and where the recovery procedure lives.

| Alert                                                   | Severity           | Means                                                                                                                          | First check                                                                                                                                                                                                   | Recovery                                                                                                                                                                                                                                                                           |
| ------------------------------------------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PublicIngestionRejectionRateHigh` / `...Critical`      | warning / critical | >20%/50% of ingestion _requests_ are being rejected (request-level ratio, >=10 requests in the window)                         | `mrrorigin_ingestion_rejected_total` by `reason` -- which rejection dominates?                                                                                                                                | `blocked_origin` → check the project's allowed-domain list; `invalid_key` → check for a recent key rotation; `invalid_payload`/`conflict` → check for a bad client SDK release. No dedicated recovery-runbook section (this is a configuration/client issue, not a resumable job). |
| `PublicIngestionServerErrorRateHigh`                    | critical           | Ingestion endpoint has returned >=3 5xx responses in 15 minutes                                                                | Application logs for the stack trace; check DB connectivity                                                                                                                                                   | N/A -- application defect, fix and redeploy.                                                                                                                                                                                                                                       |
| `StripeWebhookBacklogNotDraining`                       | warning            | >50 PENDING events, sustained                                                                                                  | `GET /api/workspaces/{id}/stripe-connection/health` per affected workspace for `pendingWebhookEvents`                                                                                                         | `docs/operations/recovery-runbook.md#stripe-webhook-events-failed-processing-and-replay`                                                                                                                                                                                           |
| `StripeWebhookOldestPendingEventTooOld` / `...Critical` | warning / critical | Oldest PENDING event >1h / >6h old                                                                                             | Is `StripeWebhookNormalizationService.processBatch` being invoked at all right now? **Known gap**: there is no `@Scheduled` caller of `processBatch` in this codebase today -- see "Known limitations" below. | Same as above; if no scheduler exists in this deployment, an operator/ops job must invoke normalization directly until that gap is closed.                                                                                                                                         |
| `StripeWebhookFailureRateHigh`                          | warning            | >10% of processed events failing (event-level ratio on both sides, >=10 total events in the window)                            | `GET /api/workspaces/{id}/stripe-webhook-events/failed` for `failureKind`                                                                                                                                     | `docs/operations/recovery-runbook.md#stripe-webhook-events-failed-processing-and-replay`                                                                                                                                                                                           |
| `StripeBackfillStalled` / `...Critical`                 | warning / critical | An eligible connection hasn't advanced its checkpoint in >6h / >24h (24h matches `StripeBillingHealthService.STALE_THRESHOLD`) | `GET /api/workspaces/{id}/stripe-connection/health` for `backfillPhase`/`connectionStatus`/`verificationStatus`                                                                                               | `docs/operations/recovery-runbook.md#stripe-backfill-interrupted-or-stuck-initial-sync`                                                                                                                                                                                            |
| `MrrCalculationFailing`                                 | critical           | `recordAndReplay` has thrown and rolled back >=2 times in 15 minutes                                                           | Application logs for the exception; this is a real defect, not the documented `unsupported` outcome                                                                                                           | N/A -- application defect, fix and redeploy; do not attempt to route around `RevenueCalculationService`.                                                                                                                                                                           |
| `MrrCalculationUnsupportedSnapshotsElevated`            | warning            | >10 currently-persisted snapshots are `UNSUPPORTED` for one reason (point-in-time gauge, not a rate)                           | Check the `reason` tag -- which `UnsupportedReason` dominates?                                                                                                                                                | Informational; may indicate a new Stripe pricing/discount shape the engine doesn't yet support. Not a data-loss condition -- `unsupported` is a documented, intentional, never-silently-converted outcome.                                                                         |
| `AttributionRecalculationStale` / `...Critical`         | warning / critical | A `RUNNING` run hasn't advanced in >1h / >6h                                                                                   | `GET /api/workspaces/{id}/projects/{id}/attribution-recalculation` for `status`/`cursorCustomerId`                                                                                                            | `docs/operations/recovery-runbook.md#attribution-recalculation-interrupted-or-retried-batch-runs` -- call `resume` (no automatic restart exists).                                                                                                                                  |
| `AttributionRecalculationFailing`                       | warning            | `runBatch` invocations have thrown >=2 times in 15 minutes                                                                     | Application logs for the exception                                                                                                                                                                            | Same recovery-runbook section; fix underlying cause before retrying `resume`.                                                                                                                                                                                                      |
| `ReportingLatencyDegraded`                              | warning            | Average reporting-endpoint latency >2s over 15m (>=10 requests in the window)                                                  | DB query plans / connection-pool saturation for the `/reporting/*` routes                                                                                                                                     | N/A -- performance investigation, no resumable job.                                                                                                                                                                                                                                |
| `ReportingErrorRateHigh`                                | critical           | Reporting endpoints have returned >=3 5xx responses in 15 minutes                                                              | Application logs                                                                                                                                                                                              | N/A -- application defect.                                                                                                                                                                                                                                                         |
| `WeeklySummaryDeliveryStaleLease`                       | warning            | A `SENDING` lease expired and wasn't reclaimed within 15m                                                                      | Is `WeeklySummaryDispatchService.tick()` still running on its 5-minute schedule?                                                                                                                              | `docs/operations/recovery-runbook.md#weekly-summary-deliveries-interrupted-or-stuck-leases`                                                                                                                                                                                        |
| `WeeklySummaryDeliveryPermanentFailuresElevated`        | warning            | >5 `PERMANENTLY_FAILED` deliveries                                                                                             | `GET .../notifications/weekly-summary/deliveries` for `lastOutcomeAmbiguous`                                                                                                                                  | `docs/operations/recovery-runbook.md#weekly-summary-deliveries-interrupted-or-stuck-leases` -- check provider logs before replaying if ambiguous.                                                                                                                                  |

## Threshold rationale summary

- **15-minute default windows**: matches this application's lowest-frequency existing scheduled job
  (`WeeklySummaryDispatchService` ticks every 5 minutes by default) with margin for a couple of missed
  ticks before alerting, appropriate for private-beta traffic volume.
- **1-hour attribution staleness**: comfortably above the time a single bounded batch (capped at 500
  customers per `AttributionRecalculationController`) should ever take.
- **6h / 24h backfill staleness**: 24h reuses the existing, already-reviewed
  `StripeBillingHealthService.STALE_THRESHOLD` constant rather than inventing a new number; 6h is a
  warning fraction of that critical bound.
- **20% / 50% ingestion rejection ratio**: 20% is well above normal background noise (occasional
  invalid keys/expired rotations); 50% means most traffic is failing, which is only plausible under a
  systemic misconfiguration.
- **10% webhook failure ratio**: Stripe webhook payloads are well-specified; a double-digit failure
  rate signals either a new unhandled event shape or a genuine upstream problem, not isolated noise.
- **>=10 request/event minimum-volume floors on every ratio alert** (review fix): a ratio computed
  from a handful of samples is statistically meaningless (1 failure out of 2 requests reads as a
  "50% rejection rate" but is not a systemic problem). 10 is a low bar for this endpoint's expected
  private-beta volume while still filtering out near-zero-traffic noise.
- **`increase(...) >= 2` or `>= 3`, never `rate(...) > 0`, on every "is this failing at all" counter
  alert** (review fix): a single isolated failure sits inside a 15-minute rate window long enough to
  keep `rate(metric[15m]) > 0` true for the _entire_ window, which trivially satisfies any `for:`
  duration shorter than 15 minutes -- indistinguishable from a genuinely repeated failure using rate
  alone. Requiring an explicit count via `increase()` fixes this. 2 is used where a single occurrence
  is plausible under normal operation (e.g. `AttributionRecalculationFailing`); 3 is used for
  request-level 5xx alerts, which are rarer and slightly more tolerant before paging.
- **10 currently-unsupported snapshots (point-in-time gauge, not a rate)**: `mrrorigin.revenue.
calculation.unsupported_snapshots` counts _current persisted state_, not events -- there is no
  meaningful "rate" to threshold. 10 is a floor above whatever small number of already-known
  unsupported historical states normally exist for a private-beta workspace set.

## Known limitations (flagged, not fixed by this slice)

- **No scheduler drives Stripe webhook normalization.** `StripeWebhookNormalizationService.processBatch`
  has no `@Scheduled` caller anywhere in this codebase -- it is invoked only by tests and (indirectly)
  by manual/operational tooling outside this repository, if any. The new
  `mrrorigin.stripe.webhook.pending`/`.oldest_pending_age_seconds` gauges and their alerts will
  therefore report a growing backlog in any deployment that doesn't independently trigger
  normalization on a schedule. This is a pre-existing gap this slice makes _visible_, not something it
  introduces or silently works around; wiring an automatic scheduler is recommended as the next #28
  follow-up (see the PR/issue for the explicit proposal) rather than invented here.
- **No scheduler drives attribution recalculation `resume`.** Same shape as above: #84 added an
  operator HTTP surface (`status`/`resume`/`restart`), by design with "no orchestration or semantics
  beyond what the service already did." The staleness gauge/alert here assumes an operator (or a
  future scheduler) periodically calls `resume`; this slice does not add that scheduler.

Per `AGENTS.md`/the issue's own instructions, this slice does not invent automatic restart/scheduling
behavior to "fix" the above -- it only makes the existing state observable.
