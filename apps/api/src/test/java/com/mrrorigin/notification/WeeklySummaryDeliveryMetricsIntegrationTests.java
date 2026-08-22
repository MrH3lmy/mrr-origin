package com.mrrorigin.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves the weekly-summary delivery status/stale-lease gauges
 * reflect real persisted {@code weekly_summary_deliveries} state, aggregated across workspaces.
 */
@SpringBootTest
@Testcontainers
class WeeklySummaryDeliveryMetricsIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID workspaceId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE workspaces CASCADE").update();
        workspaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'test', :slug)")
                .param("id", workspaceId)
                .param("slug", "w-" + workspaceId)
                .update();
        projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:id, :w, 'p', :d, :k)")
                .param("id", projectId)
                .param("w", workspaceId)
                .param("d", "metrics-" + projectId + ".example")
                .param("k", "pk-" + projectId)
                .update();
    }

    private double gauge(String name, String... tags) {
        var g = meterRegistry.find(name).tags(tags).gauge();
        return g == null ? 0 : g.value();
    }

    private void insertDelivery(String recipient, String status, String leaseUntilSql) {
        jdbc.sql("""
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start,
                             status, next_attempt_at, lease_token, lease_until)
                        VALUES (:id, :w, :p, :recipient, :email, CURRENT_DATE, :status, CURRENT_TIMESTAMP,
                                CASE WHEN :status = 'SENDING' THEN gen_random_uuid() ELSE NULL END,
                                """
                        + leaseUntilSql + ")")
                .param("id", UUID.randomUUID())
                .param("w", workspaceId)
                .param("p", projectId)
                .param("recipient", recipient)
                .param("email", "BLOCKED_MISSING_EMAIL".equals(status) ? null : recipient + "@example.com")
                .param("status", status)
                .update();
    }

    @Test
    void deliveryStatusGaugeReflectsPersistedRowsAcrossWorkspaces() {
        double pendingBefore = gauge("mrrorigin.notification.weekly_summary.deliveries", "status", "pending");
        insertDelivery("recipient-1", "PENDING", "NULL");

        UUID secondWorkspace = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'test2', :slug)")
                .param("id", secondWorkspace)
                .param("slug", "w2-" + secondWorkspace)
                .update();
        UUID secondProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:id, :w, 'p2', :d, :k)")
                .param("id", secondProject)
                .param("w", secondWorkspace)
                .param("d", "metrics-2-" + secondProject + ".example")
                .param("k", "pk-" + secondProject)
                .update();
        jdbc.sql("""
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start,
                             status, next_attempt_at)
                        VALUES (:id, :w, :p, 'recipient-2', 'recipient-2@example.com', CURRENT_DATE, 'PENDING', CURRENT_TIMESTAMP)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", secondWorkspace)
                .param("p", secondProject)
                .update();

        // Both workspaces' PENDING rows contribute to the same aggregate series -- never a per-tenant tag.
        double pendingAfter = gauge("mrrorigin.notification.weekly_summary.deliveries", "status", "pending");
        assertThat(pendingAfter).isEqualTo(pendingBefore + 2);
    }

    @Test
    void staleLeaseGaugeReflectsAnExpiredSendingLease() {
        double before = gauge("mrrorigin.notification.weekly_summary.stale_lease");
        insertDelivery("recipient-stale", "SENDING", "CURRENT_TIMESTAMP - INTERVAL '1 hour'");
        double after = gauge("mrrorigin.notification.weekly_summary.stale_lease");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void aCurrentSendingLeaseIsNotCountedAsStale() {
        double before = gauge("mrrorigin.notification.weekly_summary.stale_lease");
        insertDelivery("recipient-current", "SENDING", "CURRENT_TIMESTAMP + INTERVAL '10 minutes'");
        double after = gauge("mrrorigin.notification.weekly_summary.stale_lease");
        assertThat(after).isEqualTo(before);
    }
}
