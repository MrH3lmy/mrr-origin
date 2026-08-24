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

// #93's four phases, as documented durations from test start (seconds). Review fix: a k6
// `ramping-arrival-rate` stage ramps LINEARLY from the previous stage's rate to its own `target` over
// its full `duration` -- a single `{ target: 10, duration: '10m' }` stage following a rate-3 stage is
// therefore a 3->10 ramp across the whole 10 minutes, never actually constant at 10. Every phase below
// is instead built from a short ramp-in stage followed by a hold stage at the same target for the
// remainder of the phase, so sustained/burst/recovery are genuinely flat for (the vast majority of)
// their documented duration, not still transitioning throughout it. Warm-up is deliberately left as a
// single ramping stage -- gradually ramping up *is* its documented purpose ("let connection pools/JIT/
// caches settle"), unlike the other three phases, which must hold a constant, provable rate.
const WARMUP_RATE = 3; // ~25% of the sustained allowance
const SUSTAINED_RATE = 10; // 60 req/min/key * 10 keys = 10 req/s -- the configured default allowance
const BURST_RATE = 30; // 3x the per-key allowance
const RECOVERY_RATE = SUSTAINED_RATE;

const WARMUP_DURATION = 120; // 2m
const SUSTAINED_DURATION = 600; // 10m
const BURST_DURATION = 60; // 60s
const RECOVERY_DURATION = 120; // 2m
const RAMP_IN_DURATION = 10; // seconds spent transitioning into each of sustained/burst/recovery

// Phase boundaries (seconds elapsed since test start) used to classify each request's outcome. Kept
// at the clean documented phase durations (not the internal ramp/hold split above) -- a few seconds of
// rate transition at a phase's own start is an expected, unavoidable artifact of changing arrival
// rates at all, not evidence the phase itself isn't held constant.
const PHASE_END = {
  warmup: WARMUP_DURATION,
  sustained: WARMUP_DURATION + SUSTAINED_DURATION,
  burst: WARMUP_DURATION + SUSTAINED_DURATION + BURST_DURATION,
  recovery:
    WARMUP_DURATION + SUSTAINED_DURATION + BURST_DURATION + RECOVERY_DURATION,
};

function currentPhase(elapsedSeconds) {
  if (elapsedSeconds < PHASE_END.warmup) return "warmup";
  if (elapsedSeconds < PHASE_END.sustained) return "sustained";
  if (elapsedSeconds < PHASE_END.burst) return "burst";
  return "recovery";
}

// Global metrics (every request, every phase).
const acceptedDuration = new Trend("mrr_ingestion_accepted_duration", true);
const serverErrorRate = new Rate("mrr_ingestion_server_error_rate");
const acceptedCount = new Counter("mrr_ingestion_accepted_total");
// Exact count of events actually sent in accepted requests (not requests themselves -- 1-5
// events/request) -- review fix: the tenant-isolation verification script compares this exact number
// against `tracking_event_envelopes` row counts, replacing what was an "accepted requests x average
// events/request" eyeball estimate with a deterministic reconciliation. This script never
// intentionally sends a genuine duplicate or invalid payload, so accepted-events-persisted is the one
// reconciliation dimension that applies here.
const acceptedEventsTotal = new Counter("mrr_ingestion_accepted_events_total");
const unexpectedStatusCount = new Counter(
  "mrr_ingestion_unexpected_status_total",
);

// Phase-scoped metrics -- review fix: without these, the test could pass even if sustained traffic was
// unexpectedly throttled, burst never actually produced a single 429, or recovery stayed throttled.
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
const recoverySuccessRate = new Rate("mrr_ingestion_recovery_success_rate");

export const options = {
  scenarios: {
    ingestion_workload: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        // Phase 1: warm-up -- an intentional ramp from 0 to the warm-up rate (see comment above).
        { target: WARMUP_RATE, duration: `${WARMUP_DURATION}s` },
        // Phase 2: sustained at the configured default allowance, genuinely flat for ~98% of its
        // 10-minute duration.
        { target: SUSTAINED_RATE, duration: `${RAMP_IN_DURATION}s` },
        {
          target: SUSTAINED_RATE,
          duration: `${SUSTAINED_DURATION - RAMP_IN_DURATION}s`,
        },
        // Phase 3: burst/throttle, 3x over the per-key allowance, flat for the rest of its 60s.
        { target: BURST_RATE, duration: `${RAMP_IN_DURATION}s` },
        {
          target: BURST_RATE,
          duration: `${BURST_DURATION - RAMP_IN_DURATION}s`,
        },
        // Phase 4: recovery -- back to the sustained rate, flat for the rest of its 2 minutes, proving
        // ingestion recovers once the fixed window permits it again.
        { target: RECOVERY_RATE, duration: `${RAMP_IN_DURATION}s` },
        {
          target: RECOVERY_RATE,
          duration: `${RECOVERY_DURATION - RAMP_IN_DURATION}s`,
        },
      ],
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
    // Ingestion must actually recover once the fixed window clears.
    mrr_ingestion_recovery_success_rate: ["rate>0.95"],
  },
};

// crypto.randomUUID() (k6/experimental/webcrypto), not Math.random() -- these ids are only load-test
// fixture data with no security stakes here, but CodeQL's insecure-randomness check flags
// identifier-shaped fields (sessionId, externalUserId) generated from Math.random() regardless of
// context, and a real UUID is also a more realistic id shape than a Math.random()-derived one anyway.
function randomId(prefix) {
  return `${prefix}_${exec.scenario.iterationInTest}_${crypto.randomUUID()}`;
}

function pageviewEvent() {
  return {
    eventId: randomId("evt"),
    visitorId: randomId("vis"),
    sessionId: randomId("ses"),
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

function identifyEvent(visitorId) {
  return {
    eventId: randomId("evt"),
    visitorId,
    type: "identify",
    occurredAt: new Date().toISOString(),
    payload: { externalUserId: randomId("user") },
  };
}

/** 1-5 events/request, weighted mix of anonymous pageview and identify traffic (#93's "normal payload mix"). */
function buildBatch() {
  const eventCount = 1 + Math.floor(Math.random() * 5);
  const visitorId = randomId("vis");
  const events = [];
  for (let i = 0; i < eventCount; i++) {
    events.push(
      i === eventCount - 1 && Math.random() < 0.2
        ? identifyEvent(visitorId)
        : pageviewEvent(),
    );
  }
  return {
    body: { version: 1, batchId: randomId("batch"), events },
    eventCount,
  };
}

export function setup() {
  return { startTimeMs: Date.now() };
}

export default function (data) {
  const elapsedSeconds = (Date.now() - data.startTimeMs) / 1000;
  const phase = currentPhase(elapsedSeconds);

  // Round-robins evenly across the 10 seeded keys, matching #93's "requests spread evenly across all
  // 10 tenants."
  const keyIndex = exec.scenario.iterationInTest % KEY_COUNT;
  const built = buildBatch();
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
    recoverySuccessRate.add(accepted);
  }

  check(res, {
    "status is 2xx or 429": (r) => accepted || r.status === 429,
  });
}
