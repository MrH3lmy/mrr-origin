package com.mrrorigin.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded in-process driver for #92: repeatedly calls the existing, already claim-safe {@link
 * StripeWebhookNormalizationService#processBatch} on a schedule, so pending Stripe webhook events
 * make automatic progress in production without an operator repeatedly invoking the service or the
 * replay endpoints.
 *
 * <p>Adds no new locking of its own. {@code processBatch}'s own {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} claim plus lease-fenced apply (see that class's Javadoc) already makes two replicas -- or
 * two overlapping scheduler ticks -- calling it concurrently safe: they simply divide the pending
 * backlog instead of racing on the same row. This class only bounds how much work one tick performs.
 *
 * <p>Each {@link #tick} calls {@code processBatch} up to {@link
 * StripeWebhookNormalizationSchedulerProperties#maxBatchesPerTick} times, stopping as soon as a call
 * returns fewer rows than {@code batchSize} (the backlog for this tick is drained). If the backlog is
 * larger than one tick's bound, the next {@code fixedDelay}-scheduled tick continues draining it --
 * bounded per invocation, convergent across invocations. {@code FAILED} rows are never reclaimed by
 * {@code claimBatch} (it only selects {@code PENDING}), so a permanent/unsupported failure is never
 * hot-looped by this driver; it stays exactly where {@code StripeBillingHealthController}'s existing
 * replay endpoints expect it.
 */
@Service
class StripeWebhookNormalizationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookNormalizationScheduler.class);

    private final StripeWebhookNormalizationService normalizationService;
    private final StripeWebhookNormalizationSchedulerProperties properties;

    StripeWebhookNormalizationScheduler(
            StripeWebhookNormalizationService normalizationService, StripeWebhookNormalizationSchedulerProperties properties) {
        this.normalizationService = normalizationService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${mrrorigin.billing.webhook-normalization.fixed-delay:PT30S}",
            initialDelayString = "${mrrorigin.billing.webhook-normalization.initial-delay:PT30S}")
    void tick() {
        if (!properties.enabled()) {
            return;
        }
        drain();
    }

    /** Package-visible so tests can invoke a bounded drain deterministically, without waiting on {@code @Scheduled}. */
    DrainOutcome drain() {
        int batchSize = properties.batchSize();
        int totalFetched = 0;
        int totalProcessed = 0;
        int totalSkipped = 0;
        int totalFailed = 0;
        int batches = 0;
        while (batches < properties.maxBatchesPerTick()) {
            StripeWebhookNormalizationService.NormalizationRunOutcome outcome = normalizationService.processBatch(batchSize);
            batches++;
            totalFetched += outcome.fetched();
            totalProcessed += outcome.processed();
            totalSkipped += outcome.skipped();
            totalFailed += outcome.failed();
            if (outcome.fetched() < batchSize) {
                // Fewer rows than requested means the pending backlog is drained for now; no point
                // spending another claim query this tick.
                break;
            }
        }
        if (totalFetched > 0) {
            log.info(
                    "Stripe webhook normalization tick: {} batch(es), {} fetched, {} processed, {} skipped, {} failed.",
                    batches, totalFetched, totalProcessed, totalSkipped, totalFailed);
        }
        return new DrainOutcome(batches, totalFetched, totalProcessed, totalSkipped, totalFailed);
    }

    record DrainOutcome(int batchesRun, int fetched, int processed, int skipped, int failed) {}
}
