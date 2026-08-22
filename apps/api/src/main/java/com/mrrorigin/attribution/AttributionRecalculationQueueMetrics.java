package com.mrrorigin.attribution;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DB-backed {@code attribution_recalculation_runs} gauges (P6 observability slice, #28), aggregated
 * across every workspace/project -- untagged, since neither dimension is a small bounded enum.
 *
 * <p>{@code stale} uses an explicit, documented threshold ({@link #STALE_THRESHOLD}) on {@code
 * updated_at}, the same column {@link AttributionRecalculationService#runBatch} advances on every
 * successful batch commit: a run can only be genuinely stuck (not just between operator-triggered
 * {@code resume} calls) if that column hasn't moved in longer than any reasonable gap between
 * batches. There is no scheduler driving {@code resume} automatically today (#84 is an operator/HTTP
 * surface only, per its own Javadoc) -- this metric does not invent one; it only reports what the
 * persisted checkpoint already shows, for an operator to act on via the recovery runbook.
 */
@Component
class AttributionRecalculationQueueMetrics {

    /**
     * A RUNNING run whose checkpoint hasn't advanced in over this long is considered stale. Chosen to
     * comfortably exceed how long a single bounded batch (at most 500 customers, {@code
     * AttributionRecalculationController}'s cap) should ever take, while still catching an abandoned
     * run within a private-beta operator's normal check-in cadence.
     */
    static final Duration STALE_THRESHOLD = Duration.ofHours(1);

    AttributionRecalculationQueueMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        Gauge.builder("mrrorigin.attribution.recalculation.running", jdbc, AttributionRecalculationQueueMetrics::runningCount)
                .register(registry);
        Gauge.builder(
                        "mrrorigin.attribution.recalculation.stale",
                        jdbc,
                        target -> staleCount(target, clock))
                .register(registry);
    }

    private static double runningCount(JdbcClient jdbc) {
        return jdbc.sql("SELECT COUNT(*) FROM attribution_recalculation_runs WHERE status = 'RUNNING'")
                .query(Long.class)
                .single();
    }

    private static double staleCount(JdbcClient jdbc, Clock clock) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(STALE_THRESHOLD);
        return jdbc.sql(
                        "SELECT COUNT(*) FROM attribution_recalculation_runs WHERE status = 'RUNNING' AND updated_at < :cutoff")
                .param("cutoff", cutoff)
                .query(Long.class)
                .single();
    }
}
