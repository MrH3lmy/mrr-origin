package com.mrrorigin.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-configurable bounds for {@link StripeWebhookNormalizationScheduler} (#92). Defaults are
 * conservative private-beta values: 50 events per {@code processBatch} call, at most 10 calls per
 * tick (500 events/tick ceiling), ticking every 30 seconds. {@code enabled} lets an operator turn the
 * driver off, e.g. while investigating a suspected normalization bug -- like every property here, it
 * is bound once at application startup, so disabling it requires a redeploy/restart, not a live
 * runtime toggle.
 */
@ConfigurationProperties(prefix = "mrrorigin.billing.webhook-normalization")
public record StripeWebhookNormalizationSchedulerProperties(Boolean enabled, Integer batchSize, Integer maxBatchesPerTick) {

    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_MAX_BATCHES_PER_TICK = 10;

    public StripeWebhookNormalizationSchedulerProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        maxBatchesPerTick = maxBatchesPerTick == null ? DEFAULT_MAX_BATCHES_PER_TICK : maxBatchesPerTick;
        if (batchSize < 1 || batchSize > StripeWebhookNormalizationService.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("mrrorigin.billing.webhook-normalization.batch-size must be between 1 and "
                    + StripeWebhookNormalizationService.MAX_BATCH_SIZE);
        }
        if (maxBatchesPerTick < 1) {
            throw new IllegalArgumentException(
                    "mrrorigin.billing.webhook-normalization.max-batches-per-tick must be at least 1");
        }
    }
}
