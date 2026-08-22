package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves the attribution recalculation running/stale gauges and
 * batch counters reflect real, persisted {@code attribution_recalculation_runs} state -- not merely
 * that a meter with the right name exists.
 */
@SpringBootTest
@Testcontainers
class AttributionRecalculationMetricsIntegrationTests {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    private AttributionRecalculationService recalculation;

    @Autowired
    private JdbcClient db;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'test',:slug)")
                .param("w", workspace)
                .param("slug", "w-" + workspace)
                .update();
        db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p',:d,:k)")
                .param("p", project)
                .param("w", workspace)
                .param("d", "metrics-" + project + ".example")
                .param("k", "pk-" + project)
                .update();
    }

    private double gauge(String name) {
        var meter = meterRegistry.find(name).gauge();
        return meter == null ? 0 : meter.value();
    }

    private double counterValue(Counter counter) {
        return counter == null ? 0 : counter.count();
    }

    @Test
    void runningRunIsReflectedInTheRunningGauge() {
        double before = gauge("mrrorigin.attribution.recalculation.running");
        recalculation.runBatch(workspace, project, 10); // no candidates -> completes immediately, creating a RUNNING->COMPLETED run
        // A completed run is not RUNNING; create a second, still-open scope to prove the gauge tracks real state.
        UUID secondProject = UUID.randomUUID();
        db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p2',:d,:k)")
                .param("p", secondProject)
                .param("w", workspace)
                .param("d", "metrics-2-" + secondProject + ".example")
                .param("k", "pk-" + secondProject)
                .update();
        addCandidate(secondProject, "cus-running");
        // maxCustomers=1 with exactly one candidate completes in one call too; force RUNNING by adding
        // two candidates and a batch size of 1.
        addCandidate(secondProject, "cus-running-2");
        recalculation.runBatch(workspace, secondProject, 1);
        assertThat(gauge("mrrorigin.attribution.recalculation.running")).isGreaterThan(before);
    }

    @Test
    void staleRunIsReflectedOnceItsUpdatedAtIsOlderThanTheThreshold() {
        addCandidate(project, "cus-a");
        addCandidate(project, "cus-b");
        recalculation.runBatch(workspace, project, 1); // processes exactly 1 of 2 -> still RUNNING

        double staleBefore = gauge("mrrorigin.attribution.recalculation.stale");

        OffsetDateTime longAgo = OffsetDateTime.now(clock).minus(AttributionRecalculationQueueMetrics.STALE_THRESHOLD).minusMinutes(5);
        db.sql("UPDATE attribution_recalculation_runs SET updated_at = :at WHERE workspace_id = :w AND project_id = :p")
                .param("at", longAgo)
                .param("w", workspace)
                .param("p", project)
                .update();

        assertThat(gauge("mrrorigin.attribution.recalculation.stale")).isGreaterThan(staleBefore);
    }

    @Test
    void batchOutcomeAndCustomerCountersReflectRealBatches() {
        addCandidate(project, "cus-1");
        addCandidate(project, "cus-2");

        double completedBefore = counterValue(meterRegistry.find("mrrorigin.attribution.recalculation.batches")
                .tag("outcome", "completed")
                .counter());
        double processedBefore = counterValue(meterRegistry.find("mrrorigin.attribution.recalculation.customers_processed").counter());

        var outcome = recalculation.runBatch(workspace, project, 10);
        assertThat(outcome.complete()).isTrue();

        double completedAfter = counterValue(meterRegistry.find("mrrorigin.attribution.recalculation.batches")
                .tag("outcome", "completed")
                .counter());
        double processedAfter = counterValue(meterRegistry.find("mrrorigin.attribution.recalculation.customers_processed").counter());

        assertThat(completedAfter).isGreaterThan(completedBefore);
        assertThat(processedAfter - processedBefore).isEqualTo(2);
    }

    @Test
    void invalidInvocationIncrementsFailureCounter() {
        double before = counterValue(meterRegistry.find("mrrorigin.attribution.recalculation.failures").counter());
        try {
            recalculation.runBatch(workspace, project, -1);
        } catch (IllegalArgumentException expected) {
            // expected: maxCustomers must be positive
        }
        double after = counterValue(meterRegistry.find("mrrorigin.attribution.recalculation.failures").counter());
        assertThat(after).isGreaterThan(before);
    }

    private void addCandidate(UUID projectId, String customerId) {
        UUID movement = UUID.randomUUID();
        db.sql("""
                        INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,
                            movement_type,effective_at,calculation_version,source_billing_references)
                        VALUES(:id,:w,:c,'USD',100,'NEW',:at,'mrr-v1',ARRAY['billing:test'])
                        """)
                .param("id", movement)
                .param("w", workspace)
                .param("c", customerId)
                .param("at", OffsetDateTime.now(Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)))
                .update();
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities(id,workspace_id,project_id,external_user_id) VALUES(:i,:w,:p,:u)")
                .param("i", identity)
                .param("w", workspace)
                .param("p", projectId)
                .param("u", "user-" + customerId)
                .update();
        db.sql("""
                        INSERT INTO billing_customers(id,workspace_id,stripe_customer_id,provider_created_at,source,source_version,source_sequence)
                        VALUES(:id,:w,:c,now(),'BACKFILL',1,:c) ON CONFLICT DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", customerId)
                .update();
        db.sql("""
                        INSERT INTO stripe_customer_links(id,workspace_id,project_id,external_identity_id,stripe_customer_id,
                            evidence_source,evidence_reference,linked_by_subject_id)
                        VALUES(:id,:w,:p,:i,:c,'EXPLICIT_API','evidence','owner')
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("p", projectId)
                .param("i", identity)
                .param("c", customerId)
                .update();
    }
}
