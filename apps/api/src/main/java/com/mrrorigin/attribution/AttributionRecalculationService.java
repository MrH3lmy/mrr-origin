package com.mrrorigin.attribution;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs {@link AttributionApplicationService#recalculate} across every customer in a project's
 * recalculation scope, one bounded batch at a time, resuming from a durable checkpoint per (workspace,
 * project, {@link AttributionV1Engine#MODEL_VERSION}) -- the same "fetch a bounded slice, apply it
 * transactionally, durably advance the checkpoint" shape {@code StripeBackfillService} uses for #12,
 * adapted to a job with no external network step: because candidate selection, per-customer
 * recalculation, and the checkpoint advance are all plain database work, the whole batch can commit
 * as one transaction instead of needing StripeBackfillPageRunner's fetch-outside/apply-inside split
 * and compare-and-swap staleness check.
 *
 * <p><b>Scope.</b> A customer is in a project's recalculation scope if it currently has a
 * non-superseded {@code stripe_customer_links} row into that project, or this project has already
 * recorded an attribution result for it (so a customer that was linked, recalculated, and later
 * unlinked keeps being revisited -- otherwise its stored result would go stale forever with no run
 * to refresh it). Customers with billing activity but no link into any project yet are outside every
 * project's scope until something links them, matching {@link AttributionApplicationService#recalculate}'s
 * existing requirement that the caller supply the owning project explicitly.
 *
 * <p><b>Resumability and idempotency.</b> {@link #runBatch} processes at most {@code maxCustomers}
 * customers ordered by {@code stripe_customer_id} and advances the checkpoint past the last one
 * processed, all inside one transaction. A crash or exception before that transaction commits leaves
 * the checkpoint exactly where it was; the next call re-fetches the same candidate window and
 * reapplies it, which is safe because {@link AttributionApplicationService#recalculate} is itself
 * idempotent (upserts keyed on {@code (workspace_id, project_id, movement_id, model_version)}) --
 * retrying a batch can never create a duplicate active result.
 *
 * <p><b>Concurrency protection.</b> The run row for a given (workspace, project, model_version) is
 * read with {@code SELECT ... FOR UPDATE} before any candidate is processed, so two concurrent
 * {@link #runBatch} calls for the same scope serialize on that row rather than both computing the
 * same candidate window and duplicating work; the second call simply resumes from wherever the first
 * left the checkpoint. This is layered on top of, not a replacement for,
 * {@link AttributionApplicationService#recalculate}'s own per-customer advisory lock, which also
 * guards against a batch run and an unrelated ad hoc single-customer recalculation racing.
 */
@Service
public class AttributionRecalculationService {

    /**
     * Upper bound on {@code maxCustomers} for a single {@link #runBatch} call, shared by every caller
     * -- {@link AttributionRecalculationController}'s {@code resume} endpoint and {@link
     * AttributionRecalculationSchedulerProperties} both validate against this same constant rather than
     * each hardcoding their own 500, so the real enforcement lives in exactly one place: here, inside
     * the service itself, not just at the edges that happen to call it.
     */
    static final int MAX_BATCH_SIZE = 500;

    /**
     * Batch-outcome metrics (P6 observability slice, #28). {@code outcome} (completed/in_progress) is
     * a bounded enum; {@code mrrorigin.attribution.recalculation.customers_processed} and {@code
     * .failures} carry no tags at all -- none of these ever include a workspace/project/customer id.
     */
    private static final String BATCHES_METRIC = "mrrorigin.attribution.recalculation.batches";

    private static final String CUSTOMERS_PROCESSED_METRIC = "mrrorigin.attribution.recalculation.customers_processed";
    private static final String FAILURES_METRIC = "mrrorigin.attribution.recalculation.failures";
    private static final String BATCH_DURATION_METRIC = "mrrorigin.attribution.recalculation.batch.duration";

    private final JdbcClient db;
    private final AttributionApplicationService attribution;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    // Pre-registered at startup (rather than created lazily on first increment) so these report an
    // explicit 0, not simply absent, until something actually happens -- standard Prometheus counter
    // practice.
    private final Counter completedBatchCounter;
    private final Counter inProgressBatchCounter;
    private final Counter customersProcessedCounter;
    private final Counter failuresCounter;
    private final Timer batchDurationTimer;

    public AttributionRecalculationService(
            JdbcClient db, AttributionApplicationService attribution, Clock clock, MeterRegistry meterRegistry) {
        this.db = db;
        this.attribution = attribution;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.completedBatchCounter = Counter.builder(BATCHES_METRIC).tag("outcome", "completed").register(meterRegistry);
        this.inProgressBatchCounter = Counter.builder(BATCHES_METRIC).tag("outcome", "in_progress").register(meterRegistry);
        this.customersProcessedCounter = Counter.builder(CUSTOMERS_PROCESSED_METRIC).register(meterRegistry);
        this.failuresCounter = Counter.builder(FAILURES_METRIC).register(meterRegistry);
        this.batchDurationTimer = Timer.builder(BATCH_DURATION_METRIC).register(meterRegistry);
    }

    /**
     * Processes up to {@code maxCustomers} more customers for this project's current run, creating
     * the run if none exists yet. Safe to call repeatedly (by a future scheduler, or a retry after
     * failure): each call resumes from whatever checkpoint the previous call left behind. Returns
     * {@link BatchOutcome#complete()} {@code true} once a call processes fewer than
     * {@code maxCustomers} candidates, meaning the scope has been fully swept; further calls are
     * no-ops until {@link #restart} is invoked.
     */
    @Transactional
    public BatchOutcome runBatch(UUID workspaceId, UUID projectId, int maxCustomers) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            BatchOutcome outcome = runBatchTimed(workspaceId, projectId, maxCustomers);
            // Deferred to after-commit (P6 observability slice, #28, review fix): the per-customer
            // loop and checkpoint advance in runBatchTimed all belong to this same @Transactional
            // method, so an increment made here -- before runBatch itself returns and the proxy
            // commits -- is not yet backed by a durable write. See EventIngestionService's identical
            // reasoning.
            int customersProcessed = outcome.customersProcessedThisBatch();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    (outcome.complete() ? completedBatchCounter : inProgressBatchCounter).increment();
                    if (customersProcessed > 0) {
                        customersProcessedCounter.increment(customersProcessed);
                    }
                }
            });
            return outcome;
        } catch (RuntimeException failure) {
            failuresCounter.increment();
            throw failure;
        } finally {
            sample.stop(batchDurationTimer);
        }
    }

    private BatchOutcome runBatchTimed(UUID workspaceId, UUID projectId, int maxCustomers) {
        require(workspaceId, projectId);
        if (maxCustomers <= 0 || maxCustomers > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("maxCustomers must be between 1 and " + MAX_BATCH_SIZE);
        }
        Run run = loadOrCreateRunForUpdate(workspaceId, projectId);
        if (run.status().equals("COMPLETED")) {
            return new BatchOutcome(0, true, run.cursor(), run.processed());
        }
        List<String> customerIds = candidateCustomers(workspaceId, projectId, run.cursor(), maxCustomers);
        for (String customerId : customerIds) {
            attribution.recalculate(workspaceId, projectId, customerId);
        }
        String newCursor = customerIds.isEmpty() ? run.cursor() : customerIds.getLast();
        long processed = run.processed() + customerIds.size();
        boolean complete = customerIds.size() < maxCustomers;
        updateRun(run.id(), newCursor, processed, complete);
        return new BatchOutcome(customerIds.size(), complete, newCursor, processed);
    }

    /**
     * Starts a fresh full sweep of a completed run's scope from the beginning -- e.g. because late
     * {@code identify()} calls or new links since completion mean previously "final" results should
     * be revisited. Rejects a run that is still {@code RUNNING} rather than silently resetting an
     * in-flight sweep's progress out from under it; wait for it to complete (or let it finish via
     * further {@link #runBatch} calls) first.
     */
    @Transactional
    public void restart(UUID workspaceId, UUID projectId) {
        require(workspaceId, projectId);
        Run run = db.sql("""
                SELECT id,status,cursor_customer_id,customers_processed FROM attribution_recalculation_runs
                WHERE workspace_id=:w AND project_id=:p AND model_version=:v FOR UPDATE
                """).param("w", workspaceId).param("p", projectId).param("v", AttributionV1Engine.MODEL_VERSION)
                .query((r, n) -> new Run(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4)))
                .optional().orElseThrow(() -> new IllegalStateException("no recalculation run to restart"));
        if (!run.status().equals("COMPLETED")) {
            throw new IllegalStateException("recalculation run is still in progress");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        db.sql("""
                UPDATE attribution_recalculation_runs
                SET status='RUNNING',cursor_customer_id=NULL,customers_processed=0,updated_at=:now,completed_at=NULL
                WHERE id=:id
                """).param("now", now).param("id", run.id()).update();
    }

    /** Current progress for this project's run, if one has ever started. */
    public Optional<Run> status(UUID workspaceId, UUID projectId) {
        require(workspaceId, projectId);
        return db.sql("""
                SELECT id,status,cursor_customer_id,customers_processed FROM attribution_recalculation_runs
                WHERE workspace_id=:w AND project_id=:p AND model_version=:v
                """).param("w", workspaceId).param("p", projectId).param("v", AttributionV1Engine.MODEL_VERSION)
                .query((r, n) -> new Run(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4)))
                .optional();
    }

    private Run loadOrCreateRunForUpdate(UUID workspaceId, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        db.sql("""
                INSERT INTO attribution_recalculation_runs
                  (id,workspace_id,project_id,model_version,status,cursor_customer_id,customers_processed,started_at,updated_at)
                VALUES(:id,:w,:p,:v,'RUNNING',NULL,0,:now,:now)
                ON CONFLICT(workspace_id,project_id,model_version) DO NOTHING
                """).param("id", UUID.randomUUID()).param("w", workspaceId).param("p", projectId)
                .param("v", AttributionV1Engine.MODEL_VERSION).param("now", now).update();
        return db.sql("""
                SELECT id,status,cursor_customer_id,customers_processed FROM attribution_recalculation_runs
                WHERE workspace_id=:w AND project_id=:p AND model_version=:v FOR UPDATE
                """).param("w", workspaceId).param("p", projectId).param("v", AttributionV1Engine.MODEL_VERSION)
                .query((r, n) -> new Run(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4)))
                .single();
    }

    private void updateRun(UUID id, String cursor, long processed, boolean complete) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        db.sql("""
                UPDATE attribution_recalculation_runs
                SET cursor_customer_id=:cursor,customers_processed=:processed,updated_at=:now,
                  status=(CASE WHEN :complete THEN 'COMPLETED' ELSE 'RUNNING' END),
                  completed_at=(CASE WHEN :complete THEN :now ELSE NULL END)
                WHERE id=:id
                """).param("cursor", cursor).param("processed", processed).param("now", now)
                .param("complete", complete).param("id", id).update();
    }

    /**
     * Every candidate this query returns is guaranteed to still resolve to project {@code projectId}
     * by the time {@link AttributionApplicationService#recalculate} runs, so that method's own
     * wrong-project rejection can never trigger here and abort a batch: V8's schema makes a customer's
     * project assignment permanent once established (at most one active link per
     * {@code (workspace_id, stripe_customer_id)}, and the deferred supersession foreign key requires
     * every replacement link to stay in the same project), so a customer already recorded under
     * {@code projectId} can never have an active link elsewhere.
     */
    private List<String> candidateCustomers(UUID workspaceId, UUID projectId, String cursor, int limit) {
        return db.sql("""
                SELECT customer_id FROM (
                  SELECT stripe_customer_id AS customer_id FROM stripe_customer_links
                  WHERE workspace_id=:w AND project_id=:p AND superseded_at IS NULL
                  UNION
                  SELECT m.stripe_customer_id AS customer_id
                  FROM customer_attribution_results r
                  JOIN customer_mrr_movements m ON m.workspace_id=r.workspace_id AND m.id=r.movement_id
                  WHERE r.workspace_id=:w AND r.project_id=:p
                ) candidates
                WHERE customer_id > :cursor
                ORDER BY customer_id
                LIMIT :limit
                """).param("w", workspaceId).param("p", projectId).param("cursor", cursor == null ? "" : cursor)
                .param("limit", limit).query(String.class).list();
    }

    /**
     * Up to {@code limit} (workspace, project) scopes that do not yet have a {@code COMPLETED} run for
     * the current {@link AttributionV1Engine#MODEL_VERSION} (#92). A scope qualifies either because it
     * is within {@link #candidateCustomers}' scope definition (an active {@code stripe_customer_links}
     * row, or an already-recorded {@code customer_attribution_results} row) -- exactly as before -- or,
     * independently, because an {@code attribution_recalculation_runs} row already exists for it and is
     * not {@code COMPLETED}. That second condition closes a gap the first one alone would miss: a
     * {@code RUNNING} run whose links have since all been superseded and which has not yet committed
     * any result (e.g. its very first batch keeps failing) would otherwise have an empty candidate
     * scope and disappear from this query entirely, leaving it {@code RUNNING} forever with nothing
     * ever driving it again -- an existing run row is now always, on its own, enough to keep a scope
     * discoverable regardless of what its candidate set currently looks like. A {@code COMPLETED} scope
     * never qualifies under either condition, so a caller (e.g. {@code AttributionRecalculationScheduler})
     * driving every returned scope through one {@link #runBatch} call can never automatically
     * resume/restart a completed sweep -- that stays possible only through the explicit {@link #restart}
     * operator action.
     */
    List<Scope> pendingScopes(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return db.sql(
                        """
                        SELECT scope.workspace_id, scope.project_id
                        FROM (
                          SELECT DISTINCT workspace_id, project_id FROM stripe_customer_links WHERE superseded_at IS NULL
                          UNION
                          SELECT DISTINCT r.workspace_id, r.project_id
                          FROM customer_attribution_results r
                          UNION
                          SELECT run.workspace_id, run.project_id
                          FROM attribution_recalculation_runs run
                          WHERE run.model_version = :v AND run.status <> 'COMPLETED'
                        ) scope
                        LEFT JOIN attribution_recalculation_runs run
                          ON run.workspace_id = scope.workspace_id AND run.project_id = scope.project_id
                          AND run.model_version = :v
                        WHERE run.status IS NULL OR run.status <> 'COMPLETED'
                        ORDER BY scope.workspace_id, scope.project_id
                        LIMIT :limit
                        """)
                .param("v", AttributionV1Engine.MODEL_VERSION)
                .param("limit", limit)
                .query((r, n) -> new Scope(r.getObject(1, UUID.class), r.getObject(2, UUID.class)))
                .list();
    }

    private static void require(UUID workspaceId, UUID projectId) {
        if (workspaceId == null || projectId == null) throw new IllegalArgumentException("workspace and project are required");
    }

    public record Run(UUID id, String status, String cursor, long processed) {}

    record Scope(UUID workspaceId, UUID projectId) {}

    public record BatchOutcome(int customersProcessedThisBatch, boolean complete, String cursorCustomerId, long totalCustomersProcessed) {}
}
