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

// Custom metrics so thresholds can be scoped exactly the way #93 states them (accepted-request
// latency only; server-error rate; every response must be an expected outcome).
const acceptedDuration = new Trend("mrr_ingestion_accepted_duration", true);
const serverErrorRate = new Rate("mrr_ingestion_server_error_rate");
const rateLimitedCount = new Counter("mrr_ingestion_rate_limited_total");
const acceptedCount = new Counter("mrr_ingestion_accepted_total");
const unexpectedStatusCount = new Counter(
  "mrr_ingestion_unexpected_status_total",
);

export const options = {
  scenarios: {
    ingestion_workload: {
      executor: "ramping-arrival-rate",
      startRate: 3,
      timeUnit: "1s",
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        // Phase 1: warm-up, ~25% of the 10 req/s aggregate allowance (issue: "2 minutes at ~25%
        // target traffic").
        { target: 3, duration: "2m" },
        // Phase 2: sustained at the configured default allowance -- 60 req/min/key * 10 keys = 10 req/s.
        { target: 10, duration: "10m" },
        // Phase 3: burst/throttle, 3x over the per-key allowance, to prove rate limiting stays
        // controlled (429s) rather than degrading into 5xx/DB contention.
        { target: 30, duration: "60s" },
        // Phase 4: recovery -- back to the sustained rate, proving ingestion recovers once the fixed
        // window permits it again.
        { target: 10, duration: "2m" },
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
  return { version: 1, batchId: randomId("batch"), events };
}

export default function () {
  // Round-robins evenly across the 10 seeded keys, matching #93's "requests spread evenly across all
  // 10 tenants."
  const keyIndex = exec.scenario.iterationInTest % KEY_COUNT;
  const payload = JSON.stringify(buildBatch());
  const res = http.post(`${BASE_URL}/api/public/v1/events`, payload, {
    headers: {
      "Content-Type": "application/json",
      "X-Ingestion-Key": KEYS[keyIndex],
      Origin: ORIGINS[keyIndex],
    },
  });

  if (res.status >= 200 && res.status < 300) {
    acceptedCount.add(1);
    acceptedDuration.add(res.timings.duration);
    serverErrorRate.add(false);
  } else if (res.status === 429) {
    // Expected during the burst phase -- IngestionRateLimitInterceptor's documented contract, not a
    // failure.
    rateLimitedCount.add(1);
    serverErrorRate.add(false);
  } else if (res.status >= 500) {
    serverErrorRate.add(true);
    unexpectedStatusCount.add(1);
  } else {
    // Any other status (4xx against this script's own valid seeded fixtures) is unexpected.
    serverErrorRate.add(false);
    unexpectedStatusCount.add(1);
  }

  check(res, {
    "status is 2xx or 429": (r) =>
      (r.status >= 200 && r.status < 300) || r.status === 429,
  });
}
