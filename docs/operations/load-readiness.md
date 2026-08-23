# Private-beta load readiness (#93)

Repeatable load evidence that the public ingestion path and Stripe replay/reprocessing path remain
correct, tenant-isolated, and within documented latency/error targets at the expected private-beta
workload -- the final planned slice of #28, run after #92's background-processing model landed so the
exercise measures the production execution model rather than a known-incomplete one.

**Status of this document as merged**: the load scripts/harness below are complete, real, and
exercised as far as this repository's own test/compile checks can verify. The literal "observed
results" numbers in each section are marked **pending a run with real Docker/database/live-server
access** -- the environment this document was authored in has no Docker daemon (the same limitation
`docs/operations/observability-runbook.md`'s PR #91/#92 history already documents), so this PR could
not itself execute k6 against a live server or run the Testcontainers-based harness to produce real
p95/p99/throughput numbers. Do not treat any number below as measured until this note is removed and
replaced with an actual run's output. This is deliberate, not an oversight: fabricating placeholder
numbers presented as real measurements would be worse than leaving them blank.

## Reference environment

Document the exact configuration used for the reference run here once it has actually been executed:

- Machine/deployment: _pending -- record CPU/RAM/OS and whether this ran against a local
  `docker compose up -d postgres` + `mvn spring-boot:run`, a CI runner, or a staging deployment._
- Database: _pending -- Postgres version, storage class, connection pool size._
- Application build: _pending -- git commit SHA, JVM heap settings, `mrrorigin.*` config overrides (if
  any) in effect during the run._
- k6 version and machine k6 itself ran from (load generator resource headroom matters for trusting
  p95/p99 numbers): _pending._

## Public ingestion: workload, thresholds, and results

**Location**: `apps/api/loadtest/seed-ingestion-fixtures.sql`, `ingestion-load-test.js`,
`verify-ingestion-tenant-isolation.sql`, `run-ingestion-load-test.sh`.

### Workload profile (matches #93 exactly)

10 independent workspaces/projects, one active ingestion key per project, requests spread evenly
across all 10 tenants, 1-5 events/request with a weighted anonymous-`pageview`/`identify` mix (not one
trivial repeated JSON body). Four phases in one k6 `ramping-arrival-rate` scenario:

| Phase             | Duration | Target rate                                                             | Purpose                                                            |
| ----------------- | -------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 1. Warm-up        | 2 min    | ~3 req/s (~25% of sustained)                                            | Let connection pools/JIT/caches settle                             |
| 2. Sustained      | 10 min   | 10 req/s (60 req/min/key x 10 keys -- the configured default allowance) | Prove steady-state latency/error targets                           |
| 3. Burst/throttle | 60 s     | 30 req/s (3x the per-key allowance)                                     | Prove rate limiting stays controlled (429s), not 5xx/DB contention |
| 4. Recovery       | 2 min    | 10 req/s                                                                | Prove ingestion recovers once the fixed window permits it          |

### Pass/fail targets

- Allowed (2xx) ingestion requests: p95 <= 300 ms, p99 <= 1 s.
- Unexpected server-error (5xx) rate < 0.5%.
- During phase 3, excess requests are rejected as `429 Too Many Requests` with a `Retry-After` header
  (`IngestionRateLimitInterceptor`'s existing, unchanged contract) -- never converted into 5xx or
  timeouts.
- No cross-tenant rows, identity links, sessions, or events are created.
- Accepted/duplicate/rejected counts in persistence/metrics reconcile with the generated workload.

### How to run it

```bash
cd apps/api/loadtest
DATABASE_URL=postgres://mrr_origin:mrr_origin@localhost:5432/mrr_origin \
MRRORIGIN_BASE_URL=http://localhost:8080 \
./run-ingestion-load-test.sh
```

This seeds the 10 fixtures (idempotent, safe to re-run), runs the ~15-minute k6 scenario, and then
runs the tenant-isolation verification query. The k6 summary JSON is written to
`apps/api/loadtest/results/` (git-ignored -- copy the numbers you want to keep into this document, do
not commit the raw file, per #93's "do not commit huge raw result files" instruction).

### Interpreting 429s vs failures

A `429` during phase 3 (and possibly the tail of phase 2 if traffic is uneven) is the **expected,
correct** outcome, not a failure -- it is `IngestionRateLimitInterceptor` doing its job. The load
script's own `mrr_ingestion_server_error_rate` threshold does not count 429s as errors. Only a `5xx`,
or any response outside `{2xx, 429}` against this script's own valid seeded fixtures, indicates a real
problem. `docs/operations/observability-runbook.md`'s `PublicIngestionRejectionRateHigh`/
`PublicIngestionServerErrorRateHigh` alerts are the production-facing versions of these same signals.

### Observed results

_Pending an actual run -- see the status note at the top of this document._

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

**Convergence proof**: a second, smaller, structurally-identical backlog is drained serially
(single-threaded, one `processBatch` call at a time) as the reference. Every corresponding tenant
pair's normalized snapshot must be byte-identical between the concurrent and serial runs.

**Failure/recovery pressure**: a subset of rows has `last_attempted_at` directly backdated past
`StripeWebhookNormalizationService`'s 5-minute lease window -- exactly the persisted state a row would
be in if the worker that claimed it crashed before finishing -- without waiting 5 real minutes or
throwing a bypass exception. A fresh scheduler run must reclaim and complete them through the
unmodified real lease-expiry path.

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

_Pending an actual run -- see the status note at the top of this document._

## Failure/recovery pressure (both workloads)

Both harnesses above include a controlled-interruption scenario satisfying #93's requirement that at
least one run prove queued work is not lost, replay resumes/converges, no duplicate output is
introduced, and backlog indicators return to healthy values after recovery -- via real persisted-state
manipulation (a backdated lease), never a bypass exception. `docs/operations/recovery-runbook.md`'s
"Automatic background processing" section documents the same recovery guarantees for production
operation; this load evidence proves they hold under concurrent load, not just in isolation.

## Bottlenecks / caveats

_Pending an actual run._ Record here anything the reference run's numbers reveal: connection pool
exhaustion, GC pauses, a specific query plan, or (per #93's own instruction) a documented resource
limit of the reference environment itself that makes a target unrealistic there -- in which case record
the measured baseline and propose a revised private-beta target on #93 rather than silently weakening
the threshold in this document.

## Rerunning this evidence

Both harnesses are independently rerunnable at any time (see each section's "How to run it" above).
Neither requires provisioning permanent load-testing infrastructure -- k6 runs against whatever
instance you point `MRRORIGIN_BASE_URL` at, and the JVM harness spins up its own Testcontainers
Postgres exactly like every other integration test in this module.

## #28 status

#93 is the final planned implementation slice of #28. Update #28 with this document's actual observed
results (once run) and whether #28's load-readiness acceptance criterion is satisfied -- do not mark it
satisfied based on the pending-numbers state this document ships in.
