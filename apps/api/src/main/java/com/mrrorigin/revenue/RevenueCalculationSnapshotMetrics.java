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
 * pattern appeared. See {@link RevenueCalculationService}'s {@code mrrorigin.revenue.calculation.
 * invocations} counter for the (correctly non-inflating) per-call success/failure signal.
 *
 * <p><b>Current state only, not historical inventory (review fix).</b> {@code customer_mrr_snapshots}
 * is itself an append-only history: a customer that was unsupported at T1 and later became supported
 * at T2 still has its T1 row forever (see {@link RevenueCalculationService#replay}, which rebuilds
 * every historical instant, never deleting a real prior state). A naive {@code COUNT(*) WHERE
 * supported = false} therefore accumulates monotonically over time and would keep an operational
 * "is anything unsupported right now" alert firing long after every affected customer recovered.
 * Both gauges here instead select, per {@code (workspace_id, stripe_customer_id)}, only the row at
 * that customer's single most recent {@code effective_at} (one row is enough even when several
 * currencies were saved at that same instant -- {@link RevenueCalculationService#replay} only ever
 * writes multiple currency rows together when the whole instant is {@code supported = true}, so any
 * one of them carries the same {@code supported} flag), and count only among those latest rows.
 */
@Component
class RevenueCalculationSnapshotMetrics {

    /**
     * One row per customer's current state: {@code supported} (and, when unsupported,
     * {@code unsupported_reason}) as of that customer's single most recent {@code effective_at}.
     */
    private static final String LATEST_STATE_PER_CUSTOMER =
            """
            SELECT DISTINCT ON (workspace_id, stripe_customer_id) supported, unsupported_reason
            FROM customer_mrr_snapshots
            WHERE calculation_version = :v
            ORDER BY workspace_id, stripe_customer_id, effective_at DESC
            """;

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
        return jdbc.sql("SELECT COUNT(*) FROM (" + LATEST_STATE_PER_CUSTOMER + ") latest WHERE supported = true")
                .param("v", RevenueCalculationService.CALCULATION_VERSION)
                .query(Long.class)
                .single();
    }

    private static double unsupportedCount(JdbcClient jdbc, UnsupportedReason reason) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM (" + LATEST_STATE_PER_CUSTOMER
                                + ") latest WHERE supported = false AND unsupported_reason = :reason")
                .param("v", RevenueCalculationService.CALCULATION_VERSION)
                .param("reason", reason.name())
                .query(Long.class)
                .single();
    }
}
