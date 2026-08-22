package com.mrrorigin.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DB-backed webhook-backlog gauges (P6 observability slice, #28): a pipeline can have zero
 * processing failures while still being completely stalled (e.g. the normalization worker isn't
 * running), so "no failures" alone is not sufficient evidence of health -- the actual signal is
 * whether {@code PENDING} events are draining and how old the oldest one is. Both gauges are
 * computed live at scrape time directly from {@code stripe_webhook_events}, aggregated across every
 * workspace and tagged only by {@code mode} (test/live) -- never a workspace, connection, or event id.
 */
@Component
class StripeWebhookQueueMetrics {

    StripeWebhookQueueMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        for (StripeConnectionMode mode : StripeConnectionMode.values()) {
            String modeTag = mode.name().toLowerCase();
            Gauge.builder("mrrorigin.stripe.webhook.pending", jdbc, target -> pendingCount(target, mode))
                    .tag("mode", modeTag)
                    .register(registry);
            Gauge.builder(
                            "mrrorigin.stripe.webhook.oldest_pending_age_seconds",
                            jdbc,
                            target -> oldestPendingAgeSeconds(target, mode, clock))
                    .tag("mode", modeTag)
                    .register(registry);
        }
    }

    private static double pendingCount(JdbcClient jdbc, StripeConnectionMode mode) {
        return jdbc.sql("SELECT COUNT(*) FROM stripe_webhook_events WHERE mode = :mode AND processing_state = 'PENDING'")
                .param("mode", mode.name())
                .query(Long.class)
                .single();
    }

    private static double oldestPendingAgeSeconds(JdbcClient jdbc, StripeConnectionMode mode, Clock clock) {
        OffsetDateTime oldest = jdbc.sql(
                        "SELECT MIN(received_at) FROM stripe_webhook_events WHERE mode = :mode AND processing_state = 'PENDING'")
                .param("mode", mode.name())
                .query(OffsetDateTime.class)
                .list()
                .stream()
                .findFirst()
                .orElse(null);
        if (oldest == null) {
            return 0;
        }
        return Duration.between(oldest, OffsetDateTime.now(clock)).toSeconds();
    }
}
