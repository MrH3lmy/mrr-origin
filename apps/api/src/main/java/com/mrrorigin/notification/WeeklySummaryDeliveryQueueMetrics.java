package com.mrrorigin.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DB-backed {@code weekly_summary_deliveries} gauges (P6 observability slice, #28), aggregated
 * across every workspace/project and tagged only by {@code status} -- one of the fixed status values
 * this table already uses (never a workspace/project/recipient id). Grounded entirely in persisted
 * state: no restart/reclaim behavior is invented here, only what {@link
 * WeeklySummaryDeliveryRepository}'s existing lease/backoff design already produces.
 */
@Component
class WeeklySummaryDeliveryQueueMetrics {

    private enum Status {
        PENDING,
        SENDING,
        FAILED,
        PERMANENTLY_FAILED,
        BLOCKED_MISSING_EMAIL,
        SENT,
        CANCELLED
    }

    WeeklySummaryDeliveryQueueMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        for (Status status : Status.values()) {
            Gauge.builder("mrrorigin.notification.weekly_summary.deliveries", jdbc, target -> countByStatus(target, status))
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder(
                        "mrrorigin.notification.weekly_summary.stale_lease",
                        jdbc,
                        target -> staleLeaseCount(target, clock))
                .register(registry);
    }

    private static double countByStatus(JdbcClient jdbc, Status status) {
        return jdbc.sql("SELECT COUNT(*) FROM weekly_summary_deliveries WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    private static double staleLeaseCount(JdbcClient jdbc, Clock clock) {
        return jdbc.sql("SELECT COUNT(*) FROM weekly_summary_deliveries WHERE status = 'SENDING' AND lease_until <= :now")
                .param("now", OffsetDateTime.now(clock))
                .query(Long.class)
                .single();
    }
}
