package com.mrrorigin.workspacelifecycle;

/**
 * Workspace deletion phases (#62), always run in this order because it is dependency-safe against the
 * full cross-module foreign-key graph:
 *
 * <ul>
 *   <li>{@code ADMISSION} marks the workspace {@code DELETING}, revokes every ingestion key, and
 *       disables Stripe sync -- stopping authenticated, public-ingestion, Stripe, and scheduled-job
 *       writes before any bulk delete runs.
 *   <li>{@code REPORTING} clears {@code export_audit_log} (no dependents).
 *   <li>{@code ATTRIBUTION} clears {@code customer_attribution_results}, which otherwise restricts
 *       deleting touchpoints and Stripe customer links (V10).
 *   <li>{@code IDENTITY} clears the Stripe-customer-link and visitor-alias/external-identity chain,
 *       which otherwise restricts deleting billing customers and visitors (V6/V8/V17).
 *   <li>{@code TRACKING} clears every remaining tracking table across every project in the workspace.
 *   <li>{@code BILLING} and {@code REVENUE} clear the ledger and MRR tables (no FKs between them).
 *   <li>{@code NOTIFICATION} clears weekly-summary tables, one of which restricts on membership rows.
 *   <li>{@code WORKSPACE_ROOT} deletes the {@code workspaces} row itself, cascading the last
 *       remaining {@code projects} and {@code workspace_members} rows in the same statement -- see
 *       {@link WorkspaceDeletionRequestService} for why those two are never swept as their own phases.
 * </ul>
 */
enum WorkspaceDeletionPhase {
    ADMISSION,
    REPORTING,
    ATTRIBUTION,
    IDENTITY,
    TRACKING,
    BILLING,
    REVENUE,
    NOTIFICATION,
    WORKSPACE_ROOT,
    DONE;

    WorkspaceDeletionPhase next() {
        return switch (this) {
            case ADMISSION -> REPORTING;
            case REPORTING -> ATTRIBUTION;
            case ATTRIBUTION -> IDENTITY;
            case IDENTITY -> TRACKING;
            case TRACKING -> BILLING;
            case BILLING -> REVENUE;
            case REVENUE -> NOTIFICATION;
            case NOTIFICATION -> WORKSPACE_ROOT;
            case WORKSPACE_ROOT, DONE -> DONE;
        };
    }
}
