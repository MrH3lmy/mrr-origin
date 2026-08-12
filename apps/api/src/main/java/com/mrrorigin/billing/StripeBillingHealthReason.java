package com.mrrorigin.billing;

/**
 * Deterministic reasons behind a {@link StripeBillingHealthStatus}. A report can carry more than
 * one; {@link StripeBillingHealthService} derives the overall status as the worst severity among
 * the reasons actually present, so the reason list is always sufficient by itself to explain the
 * status without needing to inspect raw counters.
 */
public enum StripeBillingHealthReason {
    /** DEGRADED: this workspace has never authorized a Stripe connection. */
    NO_ACTIVE_CONNECTION,
    /** DEGRADED: the connection exists but is PENDING, DISCONNECTED, or REVOKED. */
    CONNECTION_NOT_ACTIVE,
    /** DEGRADED: the connection's most recent verification against Stripe did not succeed. */
    CONNECTION_UNVERIFIED,
    /** DEGRADED: at least one raw webhook event is FAILED and awaiting diagnosis/replay. */
    WEBHOOK_FAILURES_PRESENT,
    /** DEGRADED: the local ledger contains a record referencing another record that is not (yet) present. */
    RECONCILIATION_MISMATCH_PRESENT,
    /** STALE: no successful sync activity has ever been recorded for this connection. */
    NEVER_SYNCED,
    /** STALE: the most recent sync activity is older than the staleness threshold. */
    SYNC_LAG_EXCEEDED,
    /** Informational: the initial backfill has not reached DONE yet. Does not by itself degrade status. */
    BACKFILL_IN_PROGRESS,
    /** Informational: at least one webhook event could not be routed to a connection at receipt time. */
    ORPHANED_EVENTS_PRESENT
}
