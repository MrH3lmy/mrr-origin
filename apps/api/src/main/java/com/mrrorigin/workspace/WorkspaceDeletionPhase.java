package com.mrrorigin.workspace;

/**
 * Workspace-wide hard-deletion phases (#62), always run in this exact order -- unlike {@code
 * ProjectDataDeletionPhase}, which preserves evidence still referenced by attribution results, a
 * workspace deletion removes everything, so the order exists purely to satisfy foreign-key
 * dependencies (a RESTRICT-guarded row's referencing table is always cleared in an earlier phase)
 * rather than to protect anything.
 *
 * <p>{@code REVOKE_INGESTION_KEYS} and {@code DISABLE_STRIPE_SYNC} run first per the accepted #27
 * contract's sequence, ahead of any data deletion. The table-sweep phases that follow are declared
 * in leaf-to-root dependency order (see the migration comment and #62's issue body for the full FK
 * analysis); {@code WORKSPACE} is the terminal phase that deletes the {@code workspaces} row itself
 * (cascading away {@code workspace_members}, {@code projects}, and any remaining pure-cascade
 * stragglers) and writes the deletion tombstone.
 *
 * <p>{@link #next()} is ordinal-based rather than a hand-written switch, unlike {@code
 * ProjectDataDeletionPhase}'s eight cases -- with three dozen phases here, keeping the declaration
 * order and the execution order the same single source of truth removes a whole class of
 * "reordered the enum but forgot to update next()" bugs.
 */
enum WorkspaceDeletionPhase {
    REVOKE_INGESTION_KEYS,
    DISABLE_STRIPE_SYNC,
    EXPORT_AUDIT_LOG,
    WEEKLY_SUMMARY_DELIVERIES,
    STRIPE_CUSTOMER_LINK_REPAIR_AUDIT_LOG,
    TRACKING_INGESTION_FAILURES,
    TRACKING_VERIFICATION_ATTEMPTS,
    ATTRIBUTION_RESULTS,
    ATTRIBUTION_RECALCULATION_RUNS,
    TRACKING_EVENT_ENVELOPES,
    TRACKING_INGESTION_BATCHES,
    TOUCHPOINTS,
    TRACKING_SESSIONS,
    VISITOR_ALIASES,
    VISITORS,
    STRIPE_CUSTOMER_LINKS,
    EXTERNAL_IDENTITIES,
    PROJECT_INGESTION_KEYS,
    PROJECT_ALLOWED_DOMAINS,
    REVENUE_SUBSCRIPTION_STATE_ITEMS,
    REVENUE_SUBSCRIPTION_STATE_DISCOUNTS,
    REVENUE_SUBSCRIPTION_STATES,
    CUSTOMER_MRR_MOVEMENTS,
    CUSTOMER_MRR_SNAPSHOTS,
    BILLING_SUBSCRIPTION_STATUS_EVENTS,
    BILLING_SUBSCRIPTION_ITEMS,
    BILLING_SUBSCRIPTIONS,
    BILLING_INVOICES,
    BILLING_PAYMENTS,
    BILLING_REFUNDS,
    BILLING_DISCOUNTS,
    BILLING_PRICES,
    BILLING_CUSTOMERS,
    STRIPE_WEBHOOK_EVENTS,
    STRIPE_OAUTH_STATES,
    STRIPE_CONNECTIONS,
    WORKSPACE,
    DONE;

    WorkspaceDeletionPhase next() {
        int nextOrdinal = ordinal() + 1;
        WorkspaceDeletionPhase[] phases = values();
        return nextOrdinal < phases.length ? phases[nextOrdinal] : DONE;
    }
}
