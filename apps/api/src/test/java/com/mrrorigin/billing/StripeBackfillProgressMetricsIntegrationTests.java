package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves the backfill incomplete-count / stalled-age gauges
 * reflect real persisted {@code stripe_connections.sync_checkpoint} state -- a pipeline detail that
 * is otherwise invisible until an operator manually calls the health endpoint per-workspace.
 */
@Testcontainers
class StripeBackfillProgressMetricsIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Autowired
    private MeterRegistry meterRegistry;

    private double gauge(String name, String mode) {
        var g = meterRegistry.find(name).tag("mode", mode).gauge();
        return g == null ? 0 : g.value();
    }

    @Test
    void incompleteBackfillIsVisibleAndClearsOnceComplete() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_progress_metrics", StripeConnectionMode.TEST);

        double incompleteBefore = gauge("mrrorigin.stripe.backfill.incomplete", "test");
        assertThat(incompleteBefore).isGreaterThanOrEqualTo(1); // freshly-connected: checkpoint is NULL -> not DONE
        assertThat(gauge("mrrorigin.stripe.backfill.stalled_age_seconds", "test")).isGreaterThanOrEqualTo(0);

        runBackfillToCompletion(connectionId); // the stub returns empty pages for every phase

        double incompleteAfter = gauge("mrrorigin.stripe.backfill.incomplete", "test");
        assertThat(incompleteAfter).isEqualTo(incompleteBefore - 1);
    }

    @Test
    void twoWorkspacesAggregateIntoTheSameGaugeWithoutAConnectionIdTag() {
        UUID workspaceA = createWorkspace();
        insertActiveConnection(workspaceA, "acct_progress_metrics_a", StripeConnectionMode.TEST);
        double afterFirst = gauge("mrrorigin.stripe.backfill.incomplete", "test");

        UUID workspaceB = createWorkspace();
        insertActiveConnection(workspaceB, "acct_progress_metrics_b", StripeConnectionMode.TEST);
        double afterSecond = gauge("mrrorigin.stripe.backfill.incomplete", "test");

        assertThat(afterSecond).isEqualTo(afterFirst + 1);
    }
}
