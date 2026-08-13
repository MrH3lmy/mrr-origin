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
    /**
     * DEGRADED: a bounded live check against Stripe's most recent customers/subscriptions found at
     * least one that does not exist locally at all -- a gap {@link #RECONCILIATION_MISMATCH_PRESENT}
     * cannot see, since it only checks references between local records.
     */
    PROVIDER_RECONCILIATION_MISMATCH_PRESENT,
    /**
     * STALE: the oldest currently-PENDING webhook event has been unprocessed longer than the
     * staleness threshold -- a real processing backlog, not merely a quiet period with no new Stripe
     * activity (a fully-caught-up, non-empty account with no pending work is never marked stale just
     * because its ledger hasn't changed recently).
     */
    SYNC_LAG_EXCEEDED,
    /**
     * STALE: the initial backfill has not reached DONE yet, so the local ledger is not yet a complete
     * mirror of Stripe's state regardless of how recently it last changed.
     */
    BACKFILL_IN_PROGRESS,
    /** Informational: at least one webhook event could not be routed to a connection at receipt time. */
    ORPHANED_EVENTS_PRESENT,
    /**
     * Informational: the live provider spot-check did not run this time (backfill still in progress,
     * or the Stripe request itself failed). Does not by itself degrade status -- it means the absence
     * of a {@link #PROVIDER_RECONCILIATION_MISMATCH_PRESENT} reason cannot be trusted as confirmation.
     */
    PROVIDER_CHECK_UNAVAILABLE
}
