# Private-beta load readiness (#93)

Repeatable load evidence that the public ingestion path and Stripe replay/reprocessing path remain
correct, tenant-isolated, and within documented latency/error targets at the expected private-beta
workload -- the final planned slice of #28, run after #92's background-processing model landed so the
exercise measures the production execution model rather than a known-incomplete one.

**Status of this document**: both harnesses below have been executed for real against live
Docker/Postgres/a running `mrr-origin-api` instance. Every number in the "Observed results" sections is
measured, not estimated. Production code (`IngestionRateLimiter`, `StripeWebhookNormalizationService`)
is unchanged by this evidence work -- the architect decision on #93 (2026-08-24) was to keep both as-is
and fix measurement/harness gaps the first real run exposed instead. See "Bottlenecks / caveats" below
for what those gaps were and why they were harness-owned, not application-owned.

## Reference environment

- Machine/deployment: 4 vCPU, 15.7 GiB RAM, Ubuntu 24.04.4 LTS, kernel `6.18.44-fc-v21` (a Firecracker
  microVM -- an ephemeral cloud dev container, not a dedicated staging box). Postgres ran via
  `docker run postgres:16`/Testcontainers on the same host; `mrr-origin-api` ran standalone
  (`java -jar`, not `spring-boot:run`) on the same host. k6 ran on this same machine as the API server
  and database -- no separate load-generator host. Observed latencies (single-digit-to-~20ms) show no
  sign that shared-resource contention between k6 and the server under test mattered here, but a
  dedicated load-generator host would make that assumption unnecessary on a future run.
- Database: Postgres 16.15 (Debian, official `postgres:16` image) for the ingestion run, default config
  (`max_connections=100`, `shared_buffers=128MB`, untuned). Postgres 17.11 (`postgres:17-alpine`,
  Testcontainers-managed) for the Stripe replay run, per the test class's own container config.
- Application build: commit `cfb6da6535057817357094a06c5e08926ef17f21` (merge of #95 into `main`) --
  the production code actually exercised (`IngestionRateLimiter`, `StripeWebhookNormalizationService`,
  `StripeWebhookNormalizationScheduler`) is unchanged by this evidence PR, so this is the commit whose
  behavior these numbers describe. This PR's own HEAD only changes `apps/api/loadtest/*` and the
  `StripeReplayLoadIntegrationTests.java` test file. Java 21.0.10 (Ubuntu OpenJDK), Spring Boot 4.1.0.
  JVM: `-Xms512m -Xmx1536m -XX:+UseG1GC`. All `mrrorigin.*` config left at defaults
  (`INGESTION_RATE_LIMIT_PER_MINUTE=60`, Stripe scheduler `batch-size=50`/`max-batches-per-tick=10`,
  etc.) -- no config overrides in effect.
- k6 v0.54.0, run from the same machine as the API server (see above). Maven 3.9.11, Docker 29.3.1.

## Public ingestion: workload, thresholds, and results

**Location**: `apps/api/loadtest/seed-ingestion-fixtures.sql`, `ingestion-load-test.js`,
`verify-ingestion-tenant-isolation.sql`, `run-ingestion-load-test.sh`.

### Workload profile (matches #93 exactly)

10 independent workspaces/projects, one active ingestion key per project, requests spread evenly
across all 10 tenants, 1-5 events/request with a weighted anonymous-`pageview`/`identify` mix (not one
trivial repeated JSON body). Four phases, each its own k6 scenario, sequenced back-to-back via
`startTime`:

| Phase             | Duration | Target rate                                                             | Executor                | Purpose                                                            |
| ----------------- | -------- | ----------------------------------------------------------------------- | ----------------------- | ------------------------------------------------------------------ |
| 1. Warm-up        | 2 min    | 0 -> 3 req/s (~25% of sustained), ramping                               | `ramping-arrival-rate`  | Let connection pools/JIT/caches settle                             |
| 2. Sustained      | 10 min   | 10 req/s (60 req/min/key x 10 keys -- the configured default allowance) | `constant-arrival-rate` | Prove steady-state latency/error targets                           |
| 3. Burst/throttle | 60 s     | 30 req/s (3x the per-key allowance)                                     | `constant-arrival-rate` | Prove rate limiting stays controlled (429s), not 5xx/DB contention |
| 4. Recovery       | 2 min    | 10 req/s                                                                | `constant-arrival-rate` | Prove ingestion recovers once the fixed window permits it          |

Sustained/burst/recovery are each `constant-arrival-rate` scenarios with an exact `duration` and no
ramp -- that executor opens VUs immediately to hit the configured rate from its first tick, so the
full documented duration is held constant. Warm-up is deliberately left as a single ramping stage --
gradually ramping up from 0 _is_ its purpose. Sequencing phases as separate scenarios (via `startTime`)
also means every request's `exec.scenario.name` directly identifies which phase produced it, so phase
classification (below) is driven by the actual executor that scheduled the request, not reconstructed
from wall-clock elapsed time.

**Recovery phase is measured in two segments**, not as one 120s block (architect decision on #93,
2026-08-24, after the first real run -- see "Bottlenecks / caveats"): `IngestionRateLimiter` is an
intentional, wall-clock-aligned fixed-window limiter (window boundaries fall on the epoch minute, not
on each key's first use), so a burst that saturates whichever 60s window(s) it lands in leaves that
window still exhausted for however much of it remains once recovery traffic starts. That carry-over
throttling is the documented contract working as designed, not a failure. Using `exec.scenario.progress`
(k6's own authoritative 0..1 fraction of the scenario's elapsed duration):

- **Carry-over segment** (recovery's first 60s): 429s are expected here and not counted against
  success; each one must still carry a valid `Retry-After` and must never be a 5xx.
- **Fresh-window verification segment** (recovery's last 60s -- guaranteed at least one full 60s window
  boundary has passed since recovery-rate traffic began): >95% 2xx is the actual recovery pass/fail
  signal, exactly as originally intended.

### Pass/fail targets

- Allowed (2xx) ingestion requests: p95 <= 300 ms, p99 <= 1 s.
- Unexpected server-error (5xx) rate < 0.5%.
- During phase 3, excess requests are rejected as `429 Too Many Requests` with a `Retry-After` header
  (`IngestionRateLimitInterceptor`'s existing, unchanged contract) -- never converted into 5xx or
  timeouts.
- During the recovery phase's carry-over segment, the same holds for any leftover 429s; the fresh-window
  verification segment must see >95% 2xx.
- No cross-tenant rows, identity links, sessions, or events are created.
- Accepted/duplicate/rejected counts in persistence/metrics reconcile with the generated workload.

### Phase-specific proof (not just a global 429-tolerant threshold)

A single global "429s don't count as errors" threshold cannot distinguish "burst correctly throttled"
from "nothing was ever throttled at all" or "sustained traffic was throttled when it shouldn't have
been." `ingestion-load-test.js` classifies every request by its originating k6 scenario
(`exec.scenario.name`) into its phase and asserts, per phase:

- **Sustained** (`mrr_ingestion_sustained_unexpected_throttle_total <= 5`): sustained traffic sits at
  the exact configured allowance, so a little boundary/timing jitter producing an occasional 429 is
  tolerable, but more than a handful indicates real unexpected throttling.
- **Burst**: `mrr_ingestion_burst_throttled_total` must be greater than zero -- a script/environment bug
  that silently never exceeds the allowance would otherwise let this whole scenario pass without ever
  proving throttling happens. `mrr_ingestion_burst_missing_retry_after_total` must be zero (every 429
  carries `Retry-After`), and `mrr_ingestion_burst_unexpected_5xx_total` must be zero (burst never
  degrades into 5xx/DB contention).
- **Recovery carry-over** (first 60s): `mrr_ingestion_recovery_carryover_missing_retry_after_total` and
  `mrr_ingestion_recovery_carryover_unexpected_5xx_total` must both be zero.
- **Recovery verification** (last 60s, `mrr_ingestion_recovery_verify_success_rate > 0.95`): ingestion
  actually returns to successful 2xx once a full fixed window has elapsed, not just "no crash."
  (`mrr_ingestion_recovery_overall_success_rate` is still recorded across the whole 120s phase, but
  informationally only -- it mixes expected carry-over throttling with genuine recovery, so it is not
  itself a pass/fail signal.)

### How to run it

```bash
cd apps/api/loadtest
DATABASE_URL=postgres://mrr_origin:mrr_origin@localhost:5432/mrr_origin \
MRRORIGIN_BASE_URL=http://localhost:8080 \
./run-ingestion-load-test.sh
```

This seeds the 10 fixtures (idempotent, safe to re-run), captures a pre-run baseline event count,
runs the ~15-minute k6 scenario, extracts the exact `mrr_ingestion_accepted_events_total` count from the
k6 summary JSON via `jq`, and passes both the baseline and that count into the tenant-isolation/
reconciliation verification script as `-v baseline_events=<n> -v expected_events=<n>` for a
deterministic, rerun-safe reconciliation (see below). The k6 summary JSON is written to
`apps/api/loadtest/results/` (git-ignored -- copy the numbers you want to keep into this document, do
not commit the raw file, per #93's "do not commit huge raw result files" instruction). Requires `psql`,
`k6`, and `jq` on `PATH`; the script checks for all three up front and fails fast with a clear message
if one is missing.

A k6 threshold failure (exit code 99) no longer aborts the script before verification runs: k6's exit
code is captured, verification always runs on whatever k6 produced, and the script only propagates the
threshold failure (after printing it clearly) once verification has had its say. A failed performance
threshold must never suppress correctness evidence.

### Interpreting 429s vs failures

A `429` during phase 3, or during the first 60s of phase 4 (carry-over), is the **expected, correct**
outcome, not a failure -- it is `IngestionRateLimitInterceptor` doing its job. The load script's own
`mrr_ingestion_server_error_rate` threshold does not count 429s as errors. Only a `5xx`, or any response
outside `{2xx, 429}` against this script's own valid seeded fixtures, indicates a real problem.
`docs/operations/observability-runbook.md`'s `PublicIngestionRejectionRateHigh`/
`PublicIngestionServerErrorRateHigh` alerts are the production-facing versions of these same signals.

### Tenant isolation/routing and reconciliation are enforced, not eyeballed

`verify-ingestion-tenant-isolation.sql` wraps every check in a `DO $$ ... RAISE EXCEPTION ...
END $$;` block, so a violation of any of them aborts the script with a non-zero exit under
`-v ON_ERROR_STOP=1` -- a CI-usable pass/fail signal, not a printed table a human has to notice. It
performs three distinct checks, each catching a different bug class:

1. **Tenant-routing proof.** `fk_*_project` foreign keys only prove a row is internally consistent
   with whatever project it references -- a bug that consistently routes key/origin A's requests into
   project/workspace B would still produce rows that are perfectly consistent with B, and pass an
   FK-only check. To prove routing itself, `ingestion-load-test.js` tags every client-generated id
   (`visitorId`/`sessionId`/`eventId`, persisted verbatim as `external_visitor_id`/
   `external_session_id`/`external_event_id`) with `_w<N>_`, the index of the ingestion key the request
   carrying that id actually used. The verification script decodes that tag from each stored id and
   hard-asserts it matches the workspace the row is actually stored under.
2. **Explicit workspace/project consistency check.** The application-level restatement of the schema's
   own `fk_*_project` relationship (redundant with the constraint, but explicit).
3. **Deterministic, rerun-safe event reconciliation.** Because the seed script is idempotent and
   intentionally preserves prior runs' data, comparing this run's exact accepted-event count against
   the _total_ `tracking_event_envelopes` row count would make a second run against the same database
   fail even when it behaved perfectly. The script instead compares this run's own delta (current total
   minus the `:baseline_events` count captured immediately before the run) against `:expected_events`,
   the precise number of events k6 actually sent in accepted requests
   (`mrr_ingestion_accepted_events_total`) -- not an "accepted requests x average events/request"
   estimate, and not a cumulative total that punishes reruns.

psql's `:name` variable interpolation is not performed inside a dollar-quoted (`$$...$$`) PL/pgSQL
body, so `expected_events BIGINT := :expected_events;` written directly inside a `DO` block would send
the literal, unparseable text `:expected_events` to the server. The script instead interpolates both
`-v` values into a plain top-level `INSERT` (not dollar-quoted, so unambiguous) into a temp table, and
every `DO` block below reads them back with ordinary SQL. That `INSERT` also serves as the script's own
cheap proof that `-v baseline_events=...`/`-v expected_events=...` actually reached the server: an
unset variable makes the `INSERT` fail immediately with a clear error.

### Observed results

Measured `./run-ingestion-load-test.sh` run against commit `cfb6da6535057817357094a06c5e08926ef17f21`,
reference environment above. 9,182 total requests over ~15 minutes.

| Target                                               | Result                                                                 |
| ---------------------------------------------------- | ---------------------------------------------------------------------- |
| Accepted p95 <= 300 ms                               | **PASS** -- 18.84 ms                                                   |
| Accepted p99 <= 1 s                                  | **PASS** -- 23.33 ms                                                   |
| 5xx rate < 0.5%                                      | **PASS** -- 0.00% (0 / 9,182)                                          |
| Burst -> controlled 429, valid `Retry-After`, no 5xx | **PASS** -- 1,177 throttled, 0 missing `Retry-After`, 0 unexpected 5xx |
| Sustained traffic not unexpectedly throttled         | **PASS** -- 0 unexpected sustained 429s                                |
| Recovery carry-over: valid `Retry-After`, no 5xx     | **PASS** -- 0 missing, 0 unexpected 5xx                                |
| Recovery fresh-window verification > 95% 2xx         | **PASS** -- **100.00%** (601 / 601)                                    |
| Tenant-routing / isolation                           | **PASS**                                                               |
| Persisted-event reconciliation                       | **PASS** -- 23,897 events sent = 23,897 persisted, exact match         |

Recovery's whole-phase (informational only) success rate was 98.00% (1,177 / 1,201) -- the 24
carry-over 429s that made up the remaining 2% are the expected, harmless artifact the fresh-window
segment above exists to separate out from genuine recovery.

## Stripe replay/reprocessing: workload, thresholds, and results

**Location**: `apps/api/src/test/java/com/mrrorigin/billing/StripeReplayLoadIntegrationTests.java`,
tagged `load` and excluded from the default `mvn verify`/`mvn test` run.

**Why a JVM/Testcontainers harness, not k6**: normalization happens on the DB-backed
`StripeWebhookNormalizationService.processBatch` claim/lease pipeline #92's
`StripeWebhookNormalizationScheduler` now drives, not per-request HTTP calls. An HTTP tool against the
operator replay-trigger endpoint would only exercise the trigger, not the actual concurrent worker path
this issue asks to load -- the proposal posted on #93 before implementation explains this choice in
full.

### Workload profile (matches #93)

10 workspaces x 10 customer "slots" x 11 events/slot = 1,100 seeded `stripe_webhook_events` rows
(exceeds the 1,000-event minimum), covering `customer.created`, `price.created`, the full
`customer.subscription.{created,updated,updated,deleted}` lifecycle (`trialing` -> `active` ->
`past_due` -> `canceled` -- these are exactly the events PR #85 wired to synchronous MRR
recalculation), `invoice.{created,paid}`, `charge.succeeded`, `refund.created`, and
`customer.discount.created`, built from the existing `BillingFixtures` builders. All 10 workspaces
deliberately reuse the identical slot-`0..9` Stripe object id strings (Stripe ids are account-scoped,
not globally unique across accounts), stressing the `workspace_id` + Stripe-object-id keying under
genuine concurrent access rather than just sequential isolation.

Drained by 2-3 independently-constructed `StripeWebhookNormalizationScheduler` instances (the same
"simulated replicas" pattern #92's own concurrency tests use) running concurrently at #92's actual
production defaults (`batch-size=50`, `max-batches-per-tick=10`).

**Duplicates/retries**: after the first full drain, a representative subset of already-`PROCESSED`
rows is reset back to `PENDING` directly at the DB layer (simulating a redelivery/retry -- this
re-enters the real claim -> normalize -> apply pipeline, not a bypassed shortcut) and redrained; every
ledger table's row count must be unchanged afterward.

**Convergence proof, with bounded TRANSIENT replay** (architect decision on #93, 2026-08-24): a second,
smaller, structurally-identical backlog is drained serially (single-threaded, one `processBatch` call
at a time) as the reference. The two runs are isolated by sequencing, not by a production-code filter:
the serial-reference workspace is fully seeded and fully drained (backlog confirmed empty) before the
concurrent workspace's events are seeded at all, since `StripeWebhookNormalizationService.claimBatch`
claims `PENDING` rows globally -- exactly like real replicas do -- and that shared-backlog behavior is
the whole point of the load test, so it is not special-cased for this comparison. Any event left FAILED
after the first concurrent pass is required to be classified `TRANSIENT` (never `UNSUPPORTED`/`LEGACY`,
which would mean a genuine normalization bug), then requeued and redrained through the real replay path,
bounded to 3 rounds with a no-progress guard -- matching `StripeWebhookNormalizationService`'s own
documented contract ("the same event may succeed on a later replay alone") rather than asserting zero
failures after exactly one pass. Every corresponding tenant pair's normalized snapshot must be
byte-identical between the concurrent and serial runs.

**Failure/recovery pressure**: 5 of a 55-event backlog (the terminal `customer.discount.created` event
for each of 5 slots -- chosen specifically because nothing else in the seeded fixture chain depends on
it) has `last_attempted_at` directly stamped to simulate an in-flight claim. A drain first proves the
lease boundary itself is enforced -- a lease stamped moments ago (well inside
`StripeWebhookNormalizationService`'s 5-minute lease window) is correctly left alone -- then the same
rows' lease is backdated past that window -- exactly the persisted state a row would be in if the
worker that claimed it crashed before finishing -- without waiting 5 real minutes or throwing a bypass
exception. A fresh scheduler run must reclaim and complete them through the unmodified real
lease-expiry path.

### Pass/fail targets

- All eligible events converge to the same normalized billing + MRR result as the serial reference run.
- Zero duplicate durable billing rows/movements/snapshots caused by replay concurrency.
- Zero cross-workspace mutation.
- Zero unexplained permanently-pending/failed events after the workload/recovery window.
- No deadlock/livelock or unbounded retry loop.
- Unexpected processing failures < 0.5%, every failure attributable/recoverable through the existing
  status/replay model.
- The 1,000+-event reference backlog drains in <= 2 minutes.

### How to run it

```bash
cd apps/api
mvn test -Dtest=StripeReplayLoadIntegrationTests -Dexcluded.groups=
```

(`-Dexcluded.groups=` overrides `pom.xml`'s default `excluded.groups=load`, which is what keeps this
class out of the normal `mvn verify`/`mvn test` run every other PR pays for.) Requires Docker (the same
Testcontainers Postgres every other integration test in this module already uses) -- there is no
separate infrastructure to provision.

### Observed results

Measured against commit `cfb6da6535057817357094a06c5e08926ef17f21`'s production code (this evidence
PR's test-only changes on top), 8 consecutive real Testcontainers runs.

| Target                                                     | Result                                                                                                                 |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| 1,100-event backlog drains <= 2 min                        | **PASS** -- 3.0-4.3 s across all 8 runs (seed + concurrent drain + retry pass + second drain)                          |
| Unexpected processing failures < 0.5% (first pass)         | **PASS** -- 0 failures on the 1,100-event test in all 8 runs                                                           |
| Duplicate/retry: unchanged ledger row counts               | **PASS**, all 8 runs                                                                                                   |
| Cross-workspace isolation (per-workspace row counts)       | **PASS**, all 8 runs                                                                                                   |
| Serial-vs-concurrent convergence, bounded TRANSIENT replay | **PASS**, all 8 runs -- 6 of 8 needed 0 retry rounds, 2 of 8 needed exactly 1 retry round (1 `TRANSIENT` failure each) |
| Controlled interruption / lease-recovery converges         | **PASS**, all 8 runs -- lease boundary proven both ways (fresh lease left alone, expired lease reclaimed)              |

**Root cause of the intermittent `TRANSIENT` failures, confirmed precisely** (this is the same
phenomenon flagged as an open question in the first real run): every observed failure had the identical
signature -- `Referenced billing price has not been normalized yet for item si_load_2`, on a
`customer.subscription.created` event. This is exactly the out-of-order-delivery race this test class's
own header comment already documents as "minimized, not eliminated": with multiple replicas claiming
different batches concurrently from a shared backlog ordered by `received_at`, a `subscription.created`
event can occasionally land in a different concurrent batch than its prerequisite `price.created` event
and get processed first. `StripeWebhookNormalizationService.markFailed` classifies this as `TRANSIENT`
(any failure other than `StripeBillingNormalizationException`) precisely because it is expected to
succeed on replay once the prerequisite exists -- confirmed here: every occurrence cleared in exactly
one bounded retry round.

## Failure/recovery pressure (both workloads)

Both harnesses above include a controlled-interruption scenario satisfying #93's requirement that at
least one run prove queued work is not lost, replay resumes/converges, no duplicate output is
introduced, and backlog indicators return to healthy values after recovery -- via real persisted-state
manipulation (a backdated lease, or a fixed-window rate limiter's own clock, never a bypass exception).
`docs/operations/recovery-runbook.md`'s "Automatic background processing" section documents the same
recovery guarantees for production operation; this load evidence proves they hold under concurrent
load, not just in isolation.

## Bottlenecks / caveats

No application-owned bottleneck was found: p95/p99 latency stayed under 25 ms throughout (far inside
the 300 ms / 1 s targets), 5xx rate was 0% in both harnesses across every run, and the 1,100-event
Stripe backlog drained in ~3-4 seconds against a 2-minute budget. Two **measurement-harness** gaps
surfaced by the first real run were fixed here, not in production code (architect decision on #93,
2026-08-24):

1. **Ingestion recovery-phase measurement.** The original single whole-phase
   `mrr_ingestion_recovery_success_rate > 0.95` threshold counted expected carry-over 429s (the leftover
   part of whichever wall-clock-aligned fixed window burst traffic exhausted) as failures, and measured
   67.94% on the first real run purely as an artifact of that -- `IngestionRateLimiter`'s fixed-window
   design was never in question. Splitting recovery into a carry-over segment (429s expected, `Retry-After`
   still required) and a fresh-window verification segment (>95% 2xx required) resolved this without
   touching the limiter; the fresh-window segment measured 100.00% on the corrected harness.
2. **Stripe convergence assertion.** Asserting zero pending/failed immediately after one concurrent
   drain pass was stricter than `StripeWebhookNormalizationService`'s own documented contract
   (`TRANSIENT` failures are expected to succeed on a later replay). Bounded, `TRANSIENT`-only
   requeue-and-retry (capped at 3 rounds, with a no-progress guard, rejecting any non-`TRANSIENT`
   failure immediately) resolved this without touching the normalization service; every observed
   failure cleared in exactly one retry round once root-caused (see "Observed results" above).

A few smaller, purely mechanical harness bugs were also found and fixed while getting the above right,
none affecting application behavior: a `java.time.Instant` bound directly as a JDBC parameter (needed
`OffsetDateTime`, as used everywhere else in the same file) that blocked the lease-recovery scenario
from ever executing; `requeueFailedEvents`/`requeueFailedEventsForWorkspace` not clearing
`failure_kind`/`last_error` alongside the `processing_state` flip, violating
`chk_stripe_webhook_events_failure_kind_consistency`; `run-ingestion-load-test.sh` aborting on a k6
threshold failure before running tenant-isolation/reconciliation verification at all; and a `jq` path
assuming a `.values` wrapper this k6 version's summary JSON doesn't have, which silently zeroed out the
reconciliation's expected-event count.

## Rerunning this evidence

Both harnesses are independently rerunnable at any time (see each section's "How to run it" above).
Neither requires provisioning permanent load-testing infrastructure -- k6 runs against whatever
instance you point `MRRORIGIN_BASE_URL` at, and the JVM harness spins up its own Testcontainers
Postgres exactly like every other integration test in this module.

## #28 status

#93 is the final planned implementation slice of #28. #28's load-readiness acceptance criterion is
satisfied: every #93 acceptance criterion above is measured and passing, with no production code
changes required to get there.
