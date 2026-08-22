package com.mrrorigin.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DB-backed Stripe backfill/sync progress gauges (P6 observability slice, #28), aggregated across
 * every workspace and tagged only by {@code mode} (test/live) -- never a connection or workspace id.
 *
 * <p>A connection counts as backfill-incomplete when its {@code sync_checkpoint} phase is not
 * {@code DONE} (including a never-started {@code NULL} checkpoint, per {@link
 * StripeBackfillCheckpoint#INITIAL}). {@code stalled_age_seconds} is the age of the least-recently-
 * advanced incomplete, currently-eligible (ACTIVE/VERIFIED) connection's {@code updated_at} --
 * {@code updated_at} is stamped exactly when {@link StripeBackfillPageRunner#applyPage} commits a
 * checkpoint advance, so this is genuinely "time since last forward progress," not a proxy like
 * connection-creation time. Restricted to eligible connections because an ineligible one
 * (disconnected/unverified) is not expected to make progress at all -- that condition is already
 * surfaced by {@code StripeBillingHealthService}'s per-workspace health report, not this aggregate.
 */
@Component
class StripeBackfillProgressMetrics {

    StripeBackfillProgressMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        for (StripeConnectionMode mode : StripeConnectionMode.values()) {
            String modeTag = mode.name().toLowerCase();
            Gauge.builder("mrrorigin.stripe.backfill.incomplete", jdbc, target -> incompleteCount(target, mode))
                    .tag("mode", modeTag)
                    .register(registry);
            Gauge.builder(
                            "mrrorigin.stripe.backfill.stalled_age_seconds",
                            jdbc,
                            target -> stalledAgeSeconds(target, mode, clock))
                    .tag("mode", modeTag)
                    .register(registry);
        }
    }

    private static final String INCOMPLETE_ELIGIBLE_WHERE =
            """
            status = 'ACTIVE' AND verification_status = 'VERIFIED' AND mode = :mode
              AND (sync_checkpoint IS NULL OR (sync_checkpoint::jsonb ->> 'phase') <> 'DONE')
            """;

    private static double incompleteCount(JdbcClient jdbc, StripeConnectionMode mode) {
        return jdbc.sql("SELECT COUNT(*) FROM stripe_connections WHERE " + INCOMPLETE_ELIGIBLE_WHERE)
                .param("mode", mode.name())
                .query(Long.class)
                .single();
    }

    private static double stalledAgeSeconds(JdbcClient jdbc, StripeConnectionMode mode, Clock clock) {
        OffsetDateTime leastRecentlyAdvanced = jdbc.sql(
                        "SELECT MIN(updated_at) FROM stripe_connections WHERE " + INCOMPLETE_ELIGIBLE_WHERE)
                .param("mode", mode.name())
                .query(OffsetDateTime.class)
                .list()
                .stream()
                .findFirst()
                .orElse(null);
        if (leastRecentlyAdvanced == null) {
            return 0;
        }
        return Duration.between(leastRecentlyAdvanced, OffsetDateTime.now(clock)).toSeconds();
    }
}
