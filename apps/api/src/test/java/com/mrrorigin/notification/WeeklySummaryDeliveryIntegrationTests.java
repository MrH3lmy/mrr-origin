package com.mrrorigin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mrrorigin.notification.EmailSender.EmailMessage;
import com.mrrorigin.notification.WeeklySummaryDeliveryRepository.ClaimedDelivery;

/**
 * #59's delivery scheduling/retry/opt-out/recipient-resolution/idempotency, plus tenant-isolation
 * and authorization for the opt-out and manual-send/delivery-status endpoints. Uses {@link
 * FakeEmailSender} (ADR-0007's test strategy) so nothing here depends on the network; {@link
 * PostmarkEmailSenderTests} separately covers the real provider client's own request shape and
 * error classification via {@code MockRestServiceServer}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(WeeklySummaryDeliveryIntegrationTests.TestConfig.class)
class WeeklySummaryDeliveryIntegrationTests {

    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("mrrorigin.notification.email.postmark-server-token", () -> "test-token");
        registry.add("mrrorigin.notification.email.sender-address", () -> "weekly@example.com");
        // Effectively disables the real scheduled tick during this test class's run (it would
        // otherwise race the test's own direct dispatchDue()/dispatchProjectNow() calls); nothing
        // here tests the @Scheduled wiring itself, only the dispatch/retry logic it calls into.
        registry.add("mrrorigin.notification.dispatch.initial-delay", () -> "PT30M");
    }

    private static final String OWNER = "user-owner";
    private static final String ADMIN = "user-admin";
    private static final String MEMBER = "user-member";

    // A Monday, 09:00 UTC -- past the accepted Monday 08:00 delivery threshold for a UTC project,
    // so the week that just completed (2026-03-02 to 2026-03-09) is immediately due.
    private static final Instant MONDAY_0900_UTC = Instant.parse("2026-03-09T09:00:00Z");
    private static final LocalDate DUE_WEEK_START = LocalDate.parse("2026-03-02");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;
    @Autowired private WeeklySummaryDispatchService dispatchService;
    @Autowired private WeeklySummaryDeliveryRepository deliveryRepository;
    @Autowired private WeeklySummaryOptOutService optOutService;
    @Autowired private FakeEmailSender emailSender;
    @Autowired private MutableClock clock;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        emailSender.reset();
        clock.setInstant(MONDAY_0900_UTC);

        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w', :slug)")
                .param("id", workspace).param("slug", "w-" + workspace).update();
        insertMember(OWNER, "OWNER", "owner@example.com");
        insertMember(ADMIN, "ADMIN", "admin@example.com");
        insertMember(MEMBER, "MEMBER", "member@example.com");
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key, timezone) "
                        + "VALUES (:p, :w, 'p', 'one.example', :k, 'UTC')")
                .param("p", project).param("w", workspace).param("k", "pk-" + project).update();
    }

    private void insertMember(String subject, String role, String email) {
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role, email) "
                        + "VALUES (:w, :s, :r, :e)")
                .param("w", workspace).param("s", subject).param("r", role).param("e", email)
                .update();
    }

    // -- Recipient resolution, opt-out, and duplicate-send idempotency --

    @Test
    void dispatchesOnlyToManagersNotOptedOutAndIsIdempotentOnRepeat() {
        optOutService.setOptedOut(workspace, project, ADMIN, true);

        dispatchService.dispatchDue();

        assertThat(rowCount(OWNER)).isEqualTo(1);
        assertThat(rowCount(ADMIN)).isEqualTo(0); // opted out
        assertThat(rowCount(MEMBER)).isEqualTo(0); // not a manager
        assertThat(emailSender.callCount()).isEqualTo(1);
        assertThat(statusFor(OWNER)).isEqualTo("SENT");

        // A second dispatch tick for the same week must not create or send a duplicate.
        dispatchService.dispatchDue();

        assertThat(rowCount(OWNER)).isEqualTo(1);
        assertThat(emailSender.callCount()).isEqualTo(1);
    }

    @Test
    void renderedEmailUsesConfiguredSenderAndSubjectNamesTheProject() {
        dispatchService.dispatchDue();

        // Both OWNER and ADMIN are eligible and not opted out here, so both receive a message; this
        // test only cares about the OWNER's.
        assertThat(emailSender.sent()).hasSize(2);
        EmailMessage message = emailSender.sent().stream()
                .filter(sent -> sent.toAddress().equals("owner@example.com"))
                .findFirst()
                .orElseThrow();
        assertThat(message.fromAddress()).isEqualTo("weekly@example.com");
        assertThat(message.subject()).contains("p").contains("2026-03-02");
    }

    // -- Tenant isolation --

    @Test
    void deliveryRowsAreScopedToTheirOwnWorkspaceAndProject() {
        UUID otherWorkspace = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w2', :slug)")
                .param("id", otherWorkspace).param("slug", "w2-" + otherWorkspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role, email) VALUES (:w, :s, 'OWNER', :e)")
                .param("w", otherWorkspace).param("s", OWNER).param("e", "owner-other@example.com")
                .update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key, timezone) "
                        + "VALUES (:p, :w, 'p2', 'two.example', :k, 'UTC')")
                .param("p", otherProject).param("w", otherWorkspace).param("k", "pk-" + otherProject)
                .update();

        dispatchService.dispatchDue();

        // No row is ever created tagged with the wrong (workspace, project) pair -- the FK on
        // (project_id, workspace_id) would reject that combination outright if it somehow happened.
        Long crossTenantCount = db.sql(
                        "SELECT COUNT(*) FROM weekly_summary_deliveries WHERE workspace_id = :w AND project_id = :p")
                .param("w", workspace).param("p", otherProject)
                .query(Long.class).single();
        assertThat(crossTenantCount).isZero();

        // The other workspace's own project got its own delivery, correctly tagged with its own
        // workspace_id -- proves recipient resolution and delivery creation never mix the two tenants.
        Long otherTenantCount = db.sql(
                        "SELECT COUNT(*) FROM weekly_summary_deliveries WHERE workspace_id = :w AND project_id = :p")
                .param("w", otherWorkspace).param("p", otherProject)
                .query(Long.class).single();
        assertThat(otherTenantCount).isEqualTo(1);

        assertThat(rowCount(OWNER)).isEqualTo(1); // this workspace's own delivery is unaffected
    }

    // -- Timezone / DST --

    @Test
    void dueGateIsEvaluatedInEachProjectsOwnTimezoneNotUtc() {
        UUID nyProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key, timezone) "
                        + "VALUES (:p, :w, 'ny', 'ny.example', :k, 'America/New_York')")
                .param("p", nyProject).param("w", workspace).param("k", "pk-" + nyProject).update();

        // 2026-03-09T10:00:00Z = 06:00 EDT (America/New_York, already past the March 8 spring-forward
        // DST transition) -- past UTC's own 08:00 Monday threshold, but before New York's 08:00 local.
        clock.setInstant(Instant.parse("2026-03-09T10:00:00Z"));
        dispatchService.dispatchDue();

        assertThat(rowCount(OWNER)).isEqualTo(1); // UTC project: due
        assertThat(rowCountForProject(nyProject, OWNER)).isEqualTo(0); // NY project: not yet due

        // 2026-03-09T13:00:00Z = 09:00 EDT -- now past New York's own 08:00 local threshold too.
        clock.setInstant(Instant.parse("2026-03-09T13:00:00Z"));
        dispatchService.dispatchDue();

        assertThat(rowCountForProject(nyProject, OWNER)).isEqualTo(1);
    }

    // -- Retry backoff and terminal failure (repository-level, matching this codebase's existing
    // claim/lease test style for StripeWebhookNormalizationService) --

    @Test
    void transientFailureSchedulesBackoffThenEventualSuccess() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery claim1 = deliveryRepository.claimBatch(10, now).get(0);
        assertThat(claim1.attemptCount()).isEqualTo(1);
        deliveryRepository.markFailed(claim1.id(), claim1.lastAttemptedAt(), "transient error 1", false, claim1.attemptCount(), now);
        assertThat(statusFor(OWNER)).isEqualTo("FAILED");
        OffsetDateTime nextAttemptAfterFirst = nextAttemptAtFor(OWNER);
        assertThat(nextAttemptAfterFirst).isEqualTo(now.plusMinutes(1));

        // Not yet eligible before the backoff window.
        assertThat(deliveryRepository.claimBatch(10, now.plusSeconds(30))).isEmpty();

        OffsetDateTime secondAttemptNow = now.plusMinutes(1);
        ClaimedDelivery claim2 = deliveryRepository.claimBatch(10, secondAttemptNow).get(0);
        assertThat(claim2.attemptCount()).isEqualTo(2);
        boolean marked = deliveryRepository.markSent(claim2.id(), claim2.lastAttemptedAt(), "provider-msg-1", secondAttemptNow);
        assertThat(marked).isTrue();
        assertThat(statusFor(OWNER)).isEqualTo("SENT");
    }

    @Test
    void exhaustingAllAttemptsReachesPermanentlyFailed() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        OffsetDateTime attemptNow = now;
        for (int attempt = 1; attempt <= WeeklySummaryDeliveryRepository.MAX_ATTEMPTS; attempt++) {
            ClaimedDelivery claim = deliveryRepository.claimBatch(10, attemptNow).get(0);
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            deliveryRepository.markFailed(claim.id(), claim.lastAttemptedAt(), "still failing", false, claim.attemptCount(), attemptNow);
            attemptNow = nextAttemptAtFor(OWNER) != null ? nextAttemptAtFor(OWNER) : attemptNow;
        }

        assertThat(statusFor(OWNER)).isEqualTo("PERMANENTLY_FAILED");
        // A terminal row is never picked up again, at any future time.
        assertThat(deliveryRepository.claimBatch(10, attemptNow.plusDays(30))).isEmpty();
    }

    @Test
    void permanentFailureSkipsBackoffOnTheFirstAttempt() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery claim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(claim.id(), claim.lastAttemptedAt(), "invalid recipient", true, claim.attemptCount(), now);

        assertThat(statusFor(OWNER)).isEqualTo("PERMANENTLY_FAILED");
    }

    // -- Concurrency / lease fencing (mirrors BillingLedgerConcurrencyAndIsolationIntegrationTests) --

    @Test
    void staleLeaseCannotOverwriteANewerOutcome() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery staleClaim = deliveryRepository.claimBatch(10, now).get(0);
        // A second worker's claim window; simulate it by directly advancing the lease as a fresh claim
        // would (this row is SENDING, so a real second claimBatch call would correctly find nothing --
        // this directly proves the fencing that guarantee rests on).
        db.sql("UPDATE weekly_summary_deliveries SET last_attempted_at = :now2 WHERE id = :id")
                .param("now2", now.plusMinutes(1)).param("id", staleClaim.id()).update();

        boolean staleMarkSent = deliveryRepository.markSent(staleClaim.id(), staleClaim.lastAttemptedAt(), "stale-msg", now);
        assertThat(staleMarkSent).isFalse();
    }

    // -- Authorization --

    @Test
    void anyMemberCanReadAndUpdateTheirOwnOptOut() throws Exception {
        mockMvc.perform(get(optOutPath()).with(token(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optedOut").value(false));

        mockMvc.perform(put(optOutPath()).with(token(MEMBER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optedOut\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optedOut").value(true));
    }

    @Test
    void nonMemberGetsNotFoundOnOptOut() throws Exception {
        mockMvc.perform(get(optOutPath()).with(token("stranger"))).andExpect(status().isNotFound());
    }

    @Test
    void manualSendRequiresManagerRole() throws Exception {
        mockMvc.perform(post(sendPath()).with(token(MEMBER))).andExpect(status().isForbidden());
        mockMvc.perform(post(sendPath()).with(token(OWNER))).andExpect(status().isOk());
    }

    @Test
    void deliveryStatusRequiresManagerRole() throws Exception {
        mockMvc.perform(get(deliveriesPath()).with(token(MEMBER))).andExpect(status().isForbidden());
        mockMvc.perform(get(deliveriesPath()).with(token(ADMIN))).andExpect(status().isOk());
    }

    // -- Manual send trigger --

    @Test
    void manualSendCreatesAndSendsWithoutWaitingForTheScheduledThreshold() throws Exception {
        clock.setInstant(Instant.parse("2026-03-09T00:30:00Z")); // Monday, before the 08:00 threshold

        mockMvc.perform(post(sendPath()).with(token(OWNER))).andExpect(status().isOk());

        assertThat(rowCount(OWNER)).isEqualTo(1);
        assertThat(statusFor(OWNER)).isEqualTo("SENT");
    }

    // -- helpers --

    private long rowCount(String subject) {
        return rowCountForProject(project, subject);
    }

    private long rowCountForProject(UUID projectId, String subject) {
        return db.sql("SELECT COUNT(*) FROM weekly_summary_deliveries WHERE project_id = :p AND recipient_subject_id = :s")
                .param("p", projectId).param("s", subject)
                .query(Long.class).single();
    }

    private String statusFor(String subject) {
        return db.sql("SELECT status FROM weekly_summary_deliveries WHERE project_id = :p AND recipient_subject_id = :s")
                .param("p", project).param("s", subject)
                .query(String.class).single();
    }

    private OffsetDateTime nextAttemptAtFor(String subject) {
        return db.sql("SELECT next_attempt_at FROM weekly_summary_deliveries WHERE project_id = :p AND recipient_subject_id = :s")
                .param("p", project).param("s", subject)
                .query(OffsetDateTime.class).single();
    }

    private String optOutPath() {
        return "/api/workspaces/" + workspace + "/projects/" + project + "/notifications/weekly-summary/opt-out";
    }

    private String sendPath() {
        return "/api/workspaces/" + workspace + "/projects/" + project + "/notifications/weekly-summary/send";
    }

    private String deliveriesPath() {
        return "/api/workspaces/" + workspace + "/projects/" + project + "/notifications/weekly-summary/deliveries";
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(MONDAY_0900_UTC);
        }
    }

    /** A settable {@link Clock}, so retry/DST scenarios can move "now" forward within a single test. */
    static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
