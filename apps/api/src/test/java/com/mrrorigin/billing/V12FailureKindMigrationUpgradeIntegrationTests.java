package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V12 must apply cleanly against a database that already has {@code FAILED} {@code
 * stripe_webhook_events} rows from before {@code failure_kind} existed -- the normalization worker
 * (#13) has been able to mark a row {@code FAILED} since V5, well before this migration. Deliberately
 * does not use {@code @SpringBootTest}: that always runs every migration together against a fresh
 * database, which can never reproduce "V11 already applied, then V12 runs against existing data."
 * This drives Flyway directly so migrations can be applied up to V11 only, a legacy {@code FAILED}
 * row inserted exactly as pre-V12 code would have left it, and V12 applied on top of that -- the
 * scenario an already-deployed database upgrade actually goes through.
 */
@Testcontainers
class V12FailureKindMigrationUpgradeIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void v12BackfillsLegacyFailureKindForPreExistingFailedRowsInsteadOfFailingTheMigration() {
        DataSource dataSource = dataSource();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("11")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID workspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workspaces (id, name, slug, reporting_currency) VALUES (?, ?, ?, 'USD')",
                workspaceId, "Legacy workspace", "legacy-workspace-" + workspaceId);

        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                """
                INSERT INTO stripe_webhook_events
                    (id, stripe_event_id, stripe_account_id, mode, workspace_id, event_type, stripe_created_at,
                     received_at, raw_payload, payload, processing_state, attempt_count, last_attempted_at, last_error)
                VALUES (?, 'evt_legacy_failed', 'acct_legacy', 'TEST', ?, 'customer.created', ?, ?, ?, ?::jsonb,
                        'FAILED', 3, ?, 'legacy failure recorded before failure_kind existed')
                """,
                eventId, workspaceId, now, now, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "{}", now);

        // Reproduces the exact "V11 already applied, database already has a FAILED row" upgrade
        // state; this call is what would throw if V12 asserted its consistency CHECK before
        // backfilling failure_kind for rows like the one just inserted.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        String failureKind =
                jdbc.queryForObject("SELECT failure_kind FROM stripe_webhook_events WHERE id = ?", String.class, eventId);
        assertThat(failureKind).isEqualTo("LEGACY");
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
