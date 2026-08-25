// #93 public-ingestion k6 load test: warm-up -> sustained-allowed-rate -> burst/throttle ->
// recovery, against the real POST /api/public/v1/events endpoint (EventIngestionController /
// IngestionRateLimitInterceptor), using the 10 deterministic workspaces/projects/ingestion-keys
// seed-ingestion-fixtures.sql creates. See docs/operations/load-readiness.md for how to run this and
// interpret results (including why 429s during the burst phase are expected, not failures).
//
// Requires: k6 (https://k6.io/) and a running mrr-origin-api instance whose database has already been
// seeded via seed-ingestion-fixtures.sql. Does not seed the database itself -- keep seeding and load
// generation as separate, individually-rerunnable steps.

import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import exec from "k6/execution";
import { crypto } from "k6/experimental/webcrypto";

const BASE_URL = __ENV.MRRORIGIN_BASE_URL || "http://localhost:8080";
const KEY_COUNT = 10;

// Must exactly match seed-ingestion-fixtures.sql's raw_key formula -- both sides compute/hardcode the
// same deterministic raw key so no round-trip through the application is needed (see that script's
// header comment for why: ingestion keys are SHA-256-hashed at rest with no plaintext recovery path).
function loadKey(i) {
  return `mrr_loadtest0${i}_deterministicloadtestsecretvalueusedonlyforlocalk6loadtesting`;
}

const KEYS = Array.from({ length: KEY_COUNT }, (_, i) => loadKey(i));
const ORIGINS = Array.from(
  { length: KEY_COUNT },
  (_, i) => `https://loadtest${i}.example.com`,
);

// #93's four phases. Review fix (round 2): each of sustained/burst/recovery is now its own
// `constant-arrival-rate` scenario with an exact `duration` and no ramp -- that executor opens VUs
// immediately to hit the configured rate from its first tick, so the full documented duration is held
// constant, not "duration minus a ramp-in transition" (the previous ramp-in+hold approximation this
// replaces). Warm-up remains a single `ramping-arrival-rate` scenario (0 -> WARMUP_RATE over its whole
// duration) since gradually ramping up from 0 *is* its documented purpose. Scenarios are sequenced via
// `startTime` rather than stages within one scenario, which also means every request's
// `exec.scenario.name` directly identifies which phase produced it -- phase classification below is
// driven by the actual executor that scheduled the request, not reconstructed from wall-clock elapsed
// time.
const WARMUP_RATE = 3; // ~25% of the sustained allowance
const SUSTAINED_RATE = 10; // 60 req/min/key * 10 keys = 10 req/s -- the configured default allowance
const BURST_RATE = 30; // 3x the per-key allowance
const RECOVERY_RATE = SUSTAINED_RATE;

const WARMUP_DURATION = 120; // 2m
const SUSTAINED_DURATION = 600; // 10m
const BURST_DURATION = 60; // 60s
const RECOVERY_DURATION = 120; // 2m

const SUSTAINED_START = WARMUP_DURATION;
const BURST_START = SUSTAINED_START + SUSTAINED_DURATION;
const RECOVERY_START = BURST_START + BURST_DURATION;

// #93's recovery requirement is "ingestion recovers once the fixed windows permit it" -- not
// "every request succeeds the instant burst traffic stops." IngestionRateLimiter
// (apps/api/src/main/java/com/mrrorigin/tracking/IngestionRateLimiter.java) is an intentional,
// unchanged-for-#93 wall-clock-aligned fixed-window limiter (window boundaries fall on the epoch
// minute, not on each key's first use), so a burst that saturates whichever 60s window(s) it lands
// in leaves that same window still exhausted for however much of it remains once recovery traffic
// starts -- carry-over 429s in that leftover time are the documented contract working as designed,
// not a failure. Recovery's 120s is split in two to measure that honestly instead of asserting
// success across the whole phase (which conflates "expected carry-over" with "actually failed to
// recover"): the first RECOVERY_CARRYOVER_DURATION seconds can carry over throttling from the
// window burst exhausted (every such 429 must still carry a valid Retry-After -- the limiter's
// documented contract always holds, carry-over or not), and the last
// RECOVERY_VERIFY_DURATION seconds is a guaranteed-fresh-window segment (60s >= the 60s window
// length, so at least one full window boundary has passed since recovery began) where >95% 2xx is
// still required exactly as before.
const RECOVERY_CARRYOVER_DURATION = 60;
const RECOVERY_VERIFY_DURATION =
  RECOVERY_DURATION - RECOVERY_CARRYOVER_DURATION;

// Global metrics (every request, every phase).
const acceptedDuration = new Trend("mrr_ingestion_accepted_duration", true);
const serverErrorRate = new Rate("mrr_ingestion_server_error_rate");
const acceptedCount = new Counter("mrr_ingestion_accepted_total");
// Exact count of events actually sent in accepted requests (not requests themselves -- 1-5
// events/request) -- the tenant-isolation/reconciliation verification script compares this exact
// number against the *delta* in `tracking_event_envelopes` row counts for this run, replacing what was
// originally an "accepted requests x average events/request" eyeball estimate with a deterministic
// reconciliation. This script never intentionally sends a genuine duplicate or invalid payload, so
// accepted-events-persisted is the one reconciliation dimension that applies here.
const acceptedEventsTotal = new Counter("mrr_ingestion_accepted_events_total");
const unexpectedStatusCount = new Counter(
  "mrr_ingestion_unexpected_status_total",
);

// Phase-scoped metrics -- without these, the test could pass even if sustained traffic was unexpectedly
// throttled, burst never actually produced a single 429, or recovery stayed throttled.
const sustainedUnexpectedThrottle = new Counter(
  "mrr_ingestion_sustained_unexpected_throttle_total",
);
const burstThrottled = new Counter("mrr_ingestion_burst_throttled_total");
const burstMissingRetryAfter = new Counter(
  "mrr_ingestion_burst_missing_retry_after_total",
);
const burstUnexpected5xx = new Counter(
  "mrr_ingestion_burst_unexpected_5xx_total",
);
// Whole-phase recovery success rate is kept as an informational metric (no threshold) -- it mixes
// expected carry-over throttling with genuine recovery, so it is not itself a pass/fail signal.
// See RECOVERY_CARRYOVER_DURATION/RECOVERY_VERIFY_DURATION above for the two metrics that are.
const recoveryOverallSuccessRate = new Rate(
  "mrr_ingestion_recovery_overall_success_rate",
);
// Every 429 in the carry-over segment must still carry a valid Retry-After -- IngestionRateLimiter's
// documented contract holds for carry-over throttling exactly as it does for burst throttling.
const recoveryCarryoverMissingRetryAfter = new Counter(
  "mrr_ingestion_recovery_carryover_missing_retry_after_total",
);
const recoveryCarryoverUnexpected5xx = new Counter(
  "mrr_ingestion_recovery_carryover_unexpected_5xx_total",
);
// The actual pass/fail recovery signal: once at least one full fixed window has elapsed since
// recovery-rate traffic began, ingestion must actually succeed at >95%, not just "not crash."
const recoveryVerifySuccessRate = new Rate(
  "mrr_ingestion_recovery_verify_success_rate",
);

export const options = {
  // #93 requires measured p95/p99 evidence, not just threshold pass/fail -- k6's default
  // summaryTrendStats (avg/min/med/max/p90/p95) doesn't include p99's actual computed value anywhere
  // in the exported summary JSON even though a p99 threshold can reference it, so add it explicitly.
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  scenarios: {
    warmup: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 15,
      maxVUs: 60,
      stages: [{ target: WARMUP_RATE, duration: `${WARMUP_DURATION}s` }],
    },
    sustained: {
      executor: "constant-arrival-rate",
      rate: SUSTAINED_RATE,
      timeUnit: "1s",
      duration: `${SUSTAINED_DURATION}s`,
      preAllocatedVUs: 30,
      maxVUs: 100,
      startTime: `${SUSTAINED_START}s`,
    },
    burst: {
      executor: "constant-arrival-rate",
      rate: BURST_RATE,
      timeUnit: "1s",
      duration: `${BURST_DURATION}s`,
      preAllocatedVUs: 60,
      maxVUs: 150,
      startTime: `${BURST_START}s`,
    },
    recovery: {
      executor: "constant-arrival-rate",
      rate: RECOVERY_RATE,
      timeUnit: "1s",
      duration: `${RECOVERY_DURATION}s`,
      preAllocatedVUs: 30,
      maxVUs: 100,
      startTime: `${RECOVERY_START}s`,
    },
  },
  thresholds: {
    // #93's targets for allowed (2xx) ingestion requests.
    mrr_ingestion_accepted_duration: ["p(95)<300", "p(99)<1000"],
    // #93: unexpected server-error rate < 0.5%, measured across every request (not just accepted ones).
    mrr_ingestion_server_error_rate: ["rate<0.005"],
    // Every response must be an accepted 2xx or an expected 429 -- never a 4xx (this script only ever
    // sends valid keys/origins/payloads against its own seeded fixtures, so any 4xx indicates a script
    // or fixture bug) or an unhandled status.
    mrr_ingestion_unexpected_status_total: ["count==0"],
    // Sustained traffic sits at the exact configured allowance, so a little boundary/timing jitter
    // producing an occasional 429 is tolerable; anything more indicates real unexpected throttling.
    mrr_ingestion_sustained_unexpected_throttle_total: ["count<=5"],
    // The burst phase must actually produce throttling -- a script/environment bug that silently never
    // exceeds the allowance would otherwise let this whole scenario pass without ever proving #93's
    // "excess requests are rejected as 429" requirement.
    mrr_ingestion_burst_throttled_total: ["count>0"],
    // Every 429 must carry Retry-After (IngestionRateLimitInterceptor's documented contract).
    mrr_ingestion_burst_missing_retry_after_total: ["count==0"],
    // Burst must degrade into controlled 429s, never 5xx/DB contention -- #93's explicit target.
    mrr_ingestion_burst_unexpected_5xx_total: ["count==0"],
    // Carry-over 429s (the leftover part of whichever wall-clock window burst exhausted) are the
    // fixed-window limiter's documented contract working as designed -- see RECOVERY_CARRYOVER_DURATION
    // above -- but every one of them must still carry a valid Retry-After, and must never be a 5xx.
    mrr_ingestion_recovery_carryover_missing_retry_after_total: ["count==0"],
    mrr_ingestion_recovery_carryover_unexpected_5xx_total: ["count==0"],
    // The actual recovery pass/fail signal: once a full fixed window has elapsed since recovery
    // traffic began, ingestion must actually succeed at >95%, not just "not crash." (Architect
    // decision on #93, 2026-08-24: this replaces the previous whole-phase
    // mrr_ingestion_recovery_success_rate threshold, which counted expected carry-over 429s as
    // failures and was therefore stricter than #93's actual "recovers once the fixed windows permit
    // it" requirement.)
    mrr_ingestion_recovery_verify_success_rate: ["rate>0.95"],
  },
};

// crypto.randomUUID() (k6/experimental/webcrypto), not Math.random() -- these ids are only load-test
// fixture data with no security stakes here, but CodeQL's insecure-randomness check flags
// identifier-shaped fields (sessionId, externalUserId) generated from Math.random() regardless of
// context, and a real UUID is also a more realistic id shape than a Math.random()-derived one anyway.
//
// Review fix: every id is tagged with `_w<tenantIndex>_`, the index of the ingestion key/workspace the
// request carrying this id is actually sent under. `verify-ingestion-tenant-isolation.sql` decodes this
// tag from the persisted external id and asserts it matches the workspace the row landed in -- proving
// request-A-routed-to-tenant-A, not just "every row is internally FK-consistent with whatever project
// it happens to reference" (which a consistent misrouting bug would still satisfy).
function randomId(prefix, tenantIndex) {
  return `${prefix}_w${tenantIndex}_${exec.scenario.iterationInTest}_${crypto.randomUUID()}`;
}

function pageviewEvent(tenantIndex) {
  return {
    eventId: randomId("evt", tenantIndex),
    visitorId: randomId("vis", tenantIndex),
    sessionId: randomId("ses", tenantIndex),
    type: "pageview",
    occurredAt: new Date().toISOString(),
    payload: {
      landingUrl: "https://loadtest.example.com/pricing",
      referrerUrl: "https://www.google.com/",
      utmSource: "google",
      utmMedium: "cpc",
      utmCampaign: "load-test",
    },
  };
}

function identifyEvent(visitorId, tenantIndex) {
  return {
    eventId: randomId("evt", tenantIndex),
    visitorId,
    type: "identify",
    occurredAt: new Date().toISOString(),
    payload: { externalUserId: randomId("user", tenantIndex) },
  };
}

/** 1-5 events/request, weighted mix of anonymous pageview and identify traffic (#93's "normal payload mix"). */
function buildBatch(tenantIndex) {
  const eventCount = 1 + Math.floor(Math.random() * 5);
  const visitorId = randomId("vis", tenantIndex);
  const events = [];
  for (let i = 0; i < eventCount; i++) {
    events.push(
      i === eventCount - 1 && Math.random() < 0.2
        ? identifyEvent(visitorId, tenantIndex)
        : pageviewEvent(tenantIndex),
    );
  }
  return {
    body: { version: 1, batchId: randomId("batch", tenantIndex), events },
    eventCount,
  };
}

export default function () {
  // The scenario that scheduled this request identifies its phase directly -- see the scenarios
  // comment above for why this replaced elapsed-time-based classification.
  const phase = exec.scenario.name;

  // Round-robins evenly across the 10 seeded keys, matching #93's "requests spread evenly across all
  // 10 tenants." This same index is embedded into every generated id (see randomId above) so the
  // verification script can prove routing, not just internal consistency.
  const keyIndex = exec.scenario.iterationInTest % KEY_COUNT;
  const built = buildBatch(keyIndex);
  const res = http.post(
    `${BASE_URL}/api/public/v1/events`,
    JSON.stringify(built.body),
    {
      headers: {
        "Content-Type": "application/json",
        "X-Ingestion-Key": KEYS[keyIndex],
        Origin: ORIGINS[keyIndex],
      },
    },
  );

  const accepted = res.status >= 200 && res.status < 300;
  if (accepted) {
    acceptedCount.add(1);
    acceptedEventsTotal.add(built.eventCount);
    acceptedDuration.add(res.timings.duration);
    serverErrorRate.add(false);
  } else if (res.status === 429) {
    // Expected during the burst phase -- IngestionRateLimitInterceptor's documented contract, not a
    // failure.
    serverErrorRate.add(false);
  } else if (res.status >= 500) {
    serverErrorRate.add(true);
    unexpectedStatusCount.add(1);
  } else {
    // Any other status (4xx against this script's own valid seeded fixtures) is unexpected.
    serverErrorRate.add(false);
    unexpectedStatusCount.add(1);
  }

  if (phase === "sustained" && res.status === 429) {
    sustainedUnexpectedThrottle.add(1);
  } else if (phase === "burst") {
    if (res.status === 429) {
      burstThrottled.add(1);
      if (!res.headers["Retry-After"]) {
        burstMissingRetryAfter.add(1);
      }
    } else if (res.status >= 500) {
      burstUnexpected5xx.add(1);
    }
  } else if (phase === "recovery") {
    recoveryOverallSuccessRate.add(accepted);
    // exec.scenario.progress is k6's own authoritative 0..1 fraction of this scenario's configured
    // duration elapsed -- not reconstructed from wall-clock timestamps -- so this splits the recovery
    // phase into its carry-over and fresh-window-verification segments the same principled way phase
    // itself is derived from exec.scenario.name above, rather than approximating elapsed time from
    // iteration counts or Date.now().
    const elapsedInPhase = exec.scenario.progress * RECOVERY_DURATION;
    if (elapsedInPhase < RECOVERY_CARRYOVER_DURATION) {
      if (res.status === 429) {
        if (!res.headers["Retry-After"]) {
          recoveryCarryoverMissingRetryAfter.add(1);
        }
      } else if (res.status >= 500) {
        recoveryCarryoverUnexpected5xx.add(1);
      }
    } else {
      recoveryVerifySuccessRate.add(accepted);
    }
  }

  check(res, {
    "status is 2xx or 429": (r) => accepted || r.status === 429,
  });
}
