package com.mrrorigin.workspacelifecycle;

/**
 * Workspace deletion phases (#62), declared in leaf-first dependency order matching
 * ARCHITECTURE.md's module table ({@code notification} depends on {@code reporting}; {@code
 * reporting} depends on {@code attribution}, {@code revenue}; {@code attribution} depends on {@code
 * tracking}, {@code identity}, {@code revenue}; {@code revenue} depends on {@code billing}; {@code
 * identity} depends on {@code workspace}, {@code tracking}; {@code tracking} and {@code billing}
 * depend only on {@code workspace}) -- the most-dependent module is always cleared first, the same
 * ordering principle {@link WorkspaceDeletionRequestService}'s process() switch and each per-module
 * {@code *WorkspaceDataDeletionService}'s own internal table order already apply one level down.
 *
 * <p>This order is also dependency-safe against the real cross-module foreign-key graph, which is
 * strictly narrower than the module graph (most module pairs have no FK between their tables at
 * all): {@code ATTRIBUTION} clears {@code customer_attribution_results} before {@code IDENTITY} and
 * {@code TRACKING}, which it restricts (V10); {@code IDENTITY} clears {@code stripe_customer_links}
 * before {@code TRACKING} (V6 -- a visitor alias restricts its visitor) and before {@code BILLING}
 * (V8 -- a Stripe customer link restricts its billing customer). {@code REVENUE} and {@code BILLING}
 * have no foreign key between them (V7's cross-object references are plain Stripe ID columns by
 * design per that migration's comment), so their order is settled purely by the leaf-first module
 * principle: {@code revenue} depends on {@code billing}, not the reverse, so {@code REVENUE} runs
 * first.
 *
 * <ul>
 *   <li>{@code ADMISSION} marks the workspace {@code DELETING}, revokes every ingestion key, and
 *       disables Stripe sync -- stopping authenticated, public-ingestion, Stripe, and scheduled-job
 *       writes before any bulk delete runs.
 *   <li>{@code WORKSPACE_ROOT} deletes the {@code workspaces} row itself, cascading the last
 *       remaining {@code projects} and {@code workspace_members} rows (and this run's own checkpoint
 *       row) in the same statement -- see {@link WorkspaceDeletionRequestService} for why those are
 *       never swept as their own phases.
 * </ul>
 */
enum WorkspaceDeletionPhase {
    ADMISSION,
    NOTIFICATION,
    REPORTING,
    ATTRIBUTION,
    REVENUE,
    IDENTITY,
    TRACKING,
    BILLING,
    WORKSPACE_ROOT,
    DONE;

    WorkspaceDeletionPhase next() {
        return switch (this) {
            case ADMISSION -> NOTIFICATION;
            case NOTIFICATION -> REPORTING;
            case REPORTING -> ATTRIBUTION;
            case ATTRIBUTION -> REVENUE;
            case REVENUE -> IDENTITY;
            case IDENTITY -> TRACKING;
            case TRACKING -> BILLING;
            case BILLING -> WORKSPACE_ROOT;
            case WORKSPACE_ROOT, DONE -> DONE;
        };
    }
}
