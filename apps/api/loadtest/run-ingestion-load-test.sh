#!/usr/bin/env bash
# #93 one-command runner for the public-ingestion load test: seed fixtures, run k6, verify
# tenant isolation. See docs/operations/load-readiness.md for the full runbook.
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

echo "==> Running k6 ingestion load test against ${MRRORIGIN_BASE_URL} (~15 minutes: warm-up, sustained, burst, recovery)"
MRRORIGIN_BASE_URL="$MRRORIGIN_BASE_URL" k6 run \
    --summary-export "$SUMMARY_OUTPUT" \
    "${SCRIPT_DIR}/ingestion-load-test.js"

EXPECTED_EVENTS="$(jq -r '.metrics.mrr_ingestion_accepted_events_total.values.count // 0' "$SUMMARY_OUTPUT")"

echo "==> Verifying tenant isolation and reconciling accepted-event counts (expecting exactly ${EXPECTED_EVENTS} persisted events)"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v expected_events="$EXPECTED_EVENTS" \
    -f "${SCRIPT_DIR}/verify-ingestion-tenant-isolation.sql"

echo "==> Done. k6 summary written to ${SUMMARY_OUTPUT} (git-ignored; copy the numbers you want to keep into docs/operations/load-readiness.md, do not commit the raw file)."
