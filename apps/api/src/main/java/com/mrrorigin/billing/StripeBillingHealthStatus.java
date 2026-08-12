package com.mrrorigin.billing;

/** Workspace-scoped Stripe billing-data health, per #15. */
public enum StripeBillingHealthStatus {
    /** No degrading or staleness reason applies. */
    HEALTHY,
    /** Otherwise fine, but local state has not advanced recently enough to be trusted as current. */
    STALE,
    /** A concrete problem exists: no/broken connection, webhook failures, or a reconciliation mismatch. */
    DEGRADED
}
