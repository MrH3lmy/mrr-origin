#!/usr/bin/env bash
# #93 one-command runner for the public-ingestion load test: seed fixtures, capture a pre-run event
# baseline, run k6, verify tenant isolation/routing and reconcile this run's own event delta. Safe to
# rerun against an already-seeded, already-exercised database -- reconciliation compares against the
# baseline captured just before this run, not the database's cumulative total. See
# docs/operations/load-readiness.md for the full runbook.
#
# Requires: psql, k6 (https://k6.io/), jq (to extract the exact accepted-event count from k6's
# summary JSON for deterministic reconciliation), and a running mrr-origin-api instance.
#
# Usage:
#   DATABASE_URL=postgres://mrr_origin:mrr_origin@localhost:5432/mrr_origin \
#   MRRORIGIN_BASE_URL=http://localhost:8080 \
#   ./run-ingestion-load-test.sh [k6-summary-output-path]

set -euo pipefail

for dep in psql k6 jq; do
    if ! command -v "$dep" >/dev/null 2>&1; then
        echo "error: '$dep' is required but not found on PATH" >&2
        exit 1
    fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATABASE_URL="${DATABASE_URL:-postgres://mrr_origin:mrr_origin@localhost:5432/mrr_origin}"
MRRORIGIN_BASE_URL="${MRRORIGIN_BASE_URL:-http://localhost:8080}"
SUMMARY_OUTPUT="${1:-${SCRIPT_DIR}/results/ingestion-load-test-summary.json}"

mkdir -p "$(dirname "$SUMMARY_OUTPUT")"

echo "==> Seeding 10 deterministic workspaces/projects/ingestion-keys"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "${SCRIPT_DIR}/seed-ingestion-fixtures.sql"

# Captured *after* seeding (fixtures don't create event rows) and *before* k6 runs, so a rerun against
# an already-exercised database reconciles only this run's own delta, not the cumulative total left by
# every prior run against the same database.
BASELINE_EVENTS="$(psql "$DATABASE_URL" -t -A -v ON_ERROR_STOP=1 -c \
    "SELECT count(*) FROM tracking_event_envelopes WHERE workspace_id IN (SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%');")"

echo "==> Running k6 ingestion load test against ${MRRORIGIN_BASE_URL} (~15 minutes: warm-up, sustained, burst, recovery)"
# k6 exits 99 when a threshold is crossed (e.g. a real perf-target miss), which set -e would treat as
# fatal and abort the script *before* the correctness verification below ever runs -- a failed
# performance threshold must not suppress correctness evidence (#93 architect decision, 2026-08-24).
# So: capture k6's own exit code without letting set -e kill the script, run verification
# unconditionally on whatever k6 produced, and only then propagate k6's failure -- after
# verification has had its say. A verification failure itself still aborts immediately, at its own
# command, with set -e restored below.
set +e
MRRORIGIN_BASE_URL="$MRRORIGIN_BASE_URL" k6 run \
    --summary-export "$SUMMARY_OUTPUT" \
    "${SCRIPT_DIR}/ingestion-load-test.js"
K6_EXIT_CODE=$?
set -e

# No .values wrapper in this k6 version's --summary-export schema -- metrics are flat objects
# ({"count": N, "rate": N}), confirmed against a real summary file. A latent bug (the .values path
# always silently fell back to 0 via `// 0`) that was never exercised before the fix above let this
# script reach verification after a k6 threshold failure for the first time.
EXPECTED_EVENTS="$(jq -r '.metrics.mrr_ingestion_accepted_events_total.count // 0' "$SUMMARY_OUTPUT")"

echo "==> Verifying tenant isolation/routing and reconciling this run's event delta (baseline=${BASELINE_EVENTS}, expecting +${EXPECTED_EVENTS})"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v baseline_events="$BASELINE_EVENTS" -v expected_events="$EXPECTED_EVENTS" \
    -f "${SCRIPT_DIR}/verify-ingestion-tenant-isolation.sql"

echo "==> Done. k6 summary written to ${SUMMARY_OUTPUT} (git-ignored; copy the numbers you want to keep into docs/operations/load-readiness.md, do not commit the raw file)."

if [ "$K6_EXIT_CODE" -ne 0 ]; then
    echo "==> k6 recorded threshold failures (exit ${K6_EXIT_CODE}) even though correctness verification above passed. Treating this run as FAILED overall -- see the k6 output/summary for which threshold(s) missed." >&2
    exit "$K6_EXIT_CODE"
fi
