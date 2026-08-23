package com.mrrorigin.attribution;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded in-process driver for #92: advances every project's attribution recalculation scope on a
 * schedule, using the existing checkpoint/concurrency-safe {@link AttributionRecalculationService},
 * so recalculation makes automatic progress without an operator repeatedly calling PR #89's HTTP
 * surface.
 *
 * <p>Adds no new locking of its own. {@link AttributionRecalculationService#runBatch} already takes
 * {@code SELECT ... FOR UPDATE} on the run row for a scope before touching any candidate, so two
 * replicas -- or two overlapping ticks -- calling it concurrently for the same scope already
 * serialize and converge without duplicating work (see that class's Javadoc and {@code
 * AttributionRecalculationServiceIntegrationTests#concurrentBatchRunsForTheSameScopeSerializeInsteadOfDuplicatingWork}).
 *
 * <p>Each {@link #tick} calls {@link AttributionRecalculationService#pendingScopes} for up to {@link
 * AttributionRecalculationSchedulerProperties#maxScopesPerTick} scopes that are not yet {@code
 * COMPLETED}, then calls {@link AttributionRecalculationService#runBatch} <b>exactly once</b> per
 * scope, bounded to {@link AttributionRecalculationSchedulerProperties#maxCustomersPerScope}
 * customers -- one bounded batch per scope per tick, never an internal loop to completion. A scope
 * that has already reached {@code COMPLETED} is excluded by {@code pendingScopes} itself, so this
 * driver can never automatically restart a completed sweep; restart stays an explicit operator action
 * via PR #89's endpoint. A failure recalculating one scope is caught and logged so it cannot block the
 * tick's bounded work on other scopes; {@code runBatch}'s own transaction still rolls back on failure,
 * so no partial or duplicate result is ever persisted for the failed scope.
 *
 * <p><b>Known fairness limitation, not fixed here.</b> {@code pendingScopes} orders candidates
 * deterministically by {@code (workspace_id, project_id)} and applies {@code maxScopesPerTick} as a
 * plain {@code LIMIT} -- it does not rotate, round-robin, or otherwise prioritize by staleness. When
 * the number of outstanding scopes exceeds {@code maxScopesPerTick}, a scope sorting after the current
 * window is <em>not</em> guaranteed to be picked up by "the next tick": it is only reached once enough
 * higher-sorting scopes ahead of it have reached {@code COMPLETED} and dropped out of contention. A
 * scope that never converges (e.g. a batch that fails every single tick) keeps occupying its slot in
 * that ordering indefinitely, which can starve every scope sorting after it for as long as the backlog
 * stays above {@code maxScopesPerTick}. This is a real limitation, not just a theoretical one, and is
 * intentionally not addressed by widening this driver into a fairness/priority scheduler -- the
 * existing {@code AttributionRecalculationQueueMetrics} staleness gauge/alert and the operator
 * {@code status}/{@code resume}/{@code restart} endpoints (PR #89) remain the correct, already-tested
 * escape hatch for a genuinely stuck scope, exactly as before this scheduler existed.
 */
@Service
class AttributionRecalculationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttributionRecalculationScheduler.class);

    private final AttributionRecalculationService recalculation;
    private final AttributionRecalculationSchedulerProperties properties;

    AttributionRecalculationScheduler(
            AttributionRecalculationService recalculation, AttributionRecalculationSchedulerProperties properties) {
        this.recalculation = recalculation;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${mrrorigin.attribution.recalculation.scheduler.fixed-delay:PT1M}",
            initialDelayString = "${mrrorigin.attribution.recalculation.scheduler.initial-delay:PT1M}")
    void tick() {
        if (!properties.enabled()) {
            return;
        }
        driveOutstandingScopes();
    }

    /** Package-visible so tests can invoke a bounded drive deterministically, without waiting on {@code @Scheduled}. */
    DriveOutcome driveOutstandingScopes() {
        List<AttributionRecalculationService.Scope> scopes = recalculation.pendingScopes(properties.maxScopesPerTick());
        int advanced = 0;
        int failed = 0;
        for (AttributionRecalculationService.Scope scope : scopes) {
            try {
                recalculation.runBatch(scope.workspaceId(), scope.projectId(), properties.maxCustomersPerScope());
                advanced++;
            } catch (RuntimeException failure) {
                failed++;
                log.warn(
                        "Attribution recalculation scheduler batch failed for workspace {} project {}; will retry on a later tick.",
                        scope.workspaceId(), scope.projectId(), failure);
            }
        }
        if (!scopes.isEmpty()) {
            log.info(
                    "Attribution recalculation tick: {} scope(s) considered, {} advanced, {} failed.",
                    scopes.size(), advanced, failed);
        }
        return new DriveOutcome(scopes.size(), advanced, failed);
    }

    record DriveOutcome(int scopesConsidered, int scopesAdvanced, int scopesFailed) {}
}
