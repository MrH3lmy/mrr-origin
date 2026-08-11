# ADR-0002: Separate immutable inputs from derived results

- Status: Accepted
- Date: 2026-08-11

## Context

Stripe events can be duplicated, delayed, or delivered out of order. Attribution and MRR rules will evolve as edge cases are discovered. Storing only the latest calculated answer would make reconciliation and safe rule changes impossible.

## Decision

Persist raw accepted tracking/provider inputs as immutable records with idempotency keys. Normalize them into domain state, then produce versioned MRR movements and attribution results as derived data. Recalculation replaces or supersedes derived results without rewriting raw evidence.

## Consequences

- Every number can be explained and replayed.
- Storage usage is higher and retention policies must distinguish raw from derived data.
- Processing must tolerate retries and out-of-order events.
- Schema and operator tooling must expose calculation version and replay status.
