package com.mrrorigin.revenue;

import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DB-backed MRR-calculation snapshot gauges (P6 observability slice, #28, review fix), aggregated
 * across every workspace/customer -- untagged for supported, {@code reason}-tagged for unsupported.
 *
 * <p>These are deliberately gauges, not counters. {@link RevenueCalculationService#recordAndReplay}
 * rebuilds a customer's <em>entire</em> historical snapshot series on every call (see its Javadoc);
 * a counter incremented once per snapshot inside {@code saveSnapshot}/{@code saveUnsupported} would
 * recount every old, unchanged historical instant every time an unrelated later state for the same
 * customer triggers a fresh replay -- unusable for an alert claiming a genuinely new unsupported
 * pattern appeared. A gauge computed live at scrape time reports what is true right now
 * ({@code customer_mrr_snapshots.supported}), independent of how many times that truth was
 * recomputed. See {@link RevenueCalculationService}'s {@code mrrorigin.revenue.calculation.invocations}
 * counter for the (correctly non-inflating) per-call success/failure signal.
 */
@Component
class RevenueCalculationSnapshotMetrics {

    RevenueCalculationSnapshotMetrics(MeterRegistry registry, JdbcClient jdbc) {
        Gauge.builder("mrrorigin.revenue.calculation.supported_snapshots", jdbc, RevenueCalculationSnapshotMetrics::supportedCount)
                .register(registry);
        for (UnsupportedReason reason : UnsupportedReason.values()) {
            Gauge.builder(
                            "mrrorigin.revenue.calculation.unsupported_snapshots",
                            jdbc,
                            target -> unsupportedCount(target, reason))
                    .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
    }

    private static double supportedCount(JdbcClient jdbc) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE calculation_version = :v AND supported = true")
                .param("v", RevenueCalculationService.CALCULATION_VERSION)
                .query(Long.class)
                .single();
    }

    private static double unsupportedCount(JdbcClient jdbc, UnsupportedReason reason) {
        return jdbc.sql(
                        """
                        SELECT COUNT(*) FROM customer_mrr_snapshots
                        WHERE calculation_version = :v AND supported = false AND unsupported_reason = :reason
                        """)
                .param("v", RevenueCalculationService.CALCULATION_VERSION)
                .param("reason", reason.name())
                .query(Long.class)
                .single();
    }
}
