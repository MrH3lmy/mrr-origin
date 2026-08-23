#!/usr/bin/env bash
# #93 one-command runner for the public-ingestion load test: seed fixtures, run k6, verify
# tenant isolation. See docs/operations/load-readiness.md for the full runbook.
#
# Requires: psql, k6 (https://k6.io/), and a running mrr-origin-api instance.
#
# Usage:
#   DATABASE_URL=postgres://mrr_origin:mrr_origin@localhost:5432/mrr_origin \
#   MRRORIGIN_BASE_URL=http://localhost:8080 \
#   ./run-ingestion-load-test.sh [k6-summary-output-path]

set -euo pipefail

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

echo "==> Verifying tenant isolation and reconciling accepted-event counts"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "${SCRIPT_DIR}/verify-ingestion-tenant-isolation.sql"

echo "==> Done. k6 summary written to ${SUMMARY_OUTPUT} (git-ignored; copy the numbers you want to keep into docs/operations/load-readiness.md, do not commit the raw file)."
