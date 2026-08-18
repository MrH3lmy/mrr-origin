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
        registry.add("mrrorigin.notification.email.sender-address", () -> "weekly-summary@example.test");
        registry.add("mrrorigin.notification.email.web-base-url", () -> "https://app.example.test");
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

    private void insertMemberWithNoEmail(String subject, String role) {
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role, email) "
                        + "VALUES (:w, :s, :r, NULL)")
                .param("w", workspace).param("s", subject).param("r", role)
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
    void renderedEmailUsesConfiguredSenderSubjectAndContainsTheOptOutLinkAndDeliveryId() {
        dispatchService.dispatchDue();

        // Both OWNER and ADMIN are eligible and not opted out here, so both receive a message; this
        // test only cares about the OWNER's.
        assertThat(emailSender.sent()).hasSize(2);
        EmailMessage message = emailSender.sent().stream()
                .filter(sent -> sent.toAddress().equals("owner@example.com"))
                .findFirst()
                .orElseThrow();
        assertThat(message.fromAddress()).isEqualTo("weekly-summary@example.test");
        assertThat(message.subject()).contains("p").contains("2026-03-02");

        // Accepted B4: every email must link directly to the authenticated opt-out setting.
        String expectedLink = "https://app.example.test/app/" + workspace + "/projects/" + project + "/weekly-summary";
        assertThat(message.textBody()).contains(expectedLink);
        assertThat(message.htmlBody()).contains(expectedLink);

        // Delivery guarantee: the delivery id is threaded through for Postmark metadata/tracing.
        assertThat(message.deliveryId()).isEqualTo(deliveryIdFor(OWNER).toString());
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
        deliveryRepository.markFailed(claim1.id(), claim1.leaseToken(), "transient error 1", false, false, claim1.attemptCount(), now);
        assertThat(statusFor(OWNER)).isEqualTo("FAILED");
        assertThat(ambiguousFor(OWNER)).isFalse();
        OffsetDateTime nextAttemptAfterFirst = nextAttemptAtFor(OWNER);
        assertThat(nextAttemptAfterFirst).isEqualTo(now.plusMinutes(1));

        // Not yet eligible before the backoff window.
        assertThat(deliveryRepository.claimBatch(10, now.plusSeconds(30))).isEmpty();

        OffsetDateTime secondAttemptNow = now.plusMinutes(1);
        ClaimedDelivery claim2 = deliveryRepository.claimBatch(10, secondAttemptNow).get(0);
        assertThat(claim2.attemptCount()).isEqualTo(2);
        boolean marked = deliveryRepository.markSent(claim2.id(), claim2.leaseToken(), "provider-msg-1", secondAttemptNow);
        assertThat(marked).isTrue();
        assertThat(statusFor(OWNER)).isEqualTo("SENT");
    }

    // -- Workspace deletion (#62) --

    @Test
    void claimBatchExcludesDeliveriesForAWorkspaceThatIsDeleting() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        db.sql("UPDATE workspaces SET status = 'DELETING' WHERE id = :w").param("w", workspace).update();

        // #62: a delivery row queued before the workspace entered DELETING must never actually be
        // sent -- the workspace-deletion NOTIFICATION phase is about to hard-delete this row
        // regardless, and a scheduled dispatch tick racing that phase must not email it in the
        // meantime. Only the ProjectRepository candidate-query filter (stopping *new* rows from being
        // created for a DELETING workspace) existed before this fix; an already-PENDING row had no
        // guard at all until claimBatch's claimable-row query gained one.
        assertThat(deliveryRepository.claimBatch(10, now)).isEmpty();
        assertThat(statusFor(OWNER)).isEqualTo("PENDING"); // never claimed, so untouched

        db.sql("UPDATE workspaces SET status = 'ACTIVE' WHERE id = :w").param("w", workspace).update();
        assertThat(deliveryRepository.claimBatch(10, now)).hasSize(1);
    }

    @Test
    void ambiguousNetworkFailureIsRecordedDistinctlyInTheAuditTrail() {
        // Delivery guarantee (corrected, required): an ambiguous outcome (we don't know if Postmark
        // ever received the request) must be recorded distinctly, never folded into an ordinary
        // transient failure.
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery claim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(claim.id(), claim.leaseToken(), "connection reset", false, true, claim.attemptCount(), now);

        assertThat(statusFor(OWNER)).isEqualTo("FAILED");
        assertThat(ambiguousFor(OWNER)).isTrue();
    }

    @Test
    void ambiguousOutcomeSurvivesALaterDefiniteSuccess() {
        // Review fix: last_outcome_ambiguous must accumulate across attempts of the same delivery, not
        // be erased by a later success -- otherwise the audit trail would silently lose the evidence
        // that an earlier ambiguous attempt might have already sent a duplicate.
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery firstAttempt = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(
                firstAttempt.id(), firstAttempt.leaseToken(), "connection reset", false, true, firstAttempt.attemptCount(), now);
        assertThat(ambiguousFor(OWNER)).isTrue();

        OffsetDateTime secondAttemptNow = now.plusMinutes(1);
        ClaimedDelivery secondAttempt = deliveryRepository.claimBatch(10, secondAttemptNow).get(0);
        boolean marked = deliveryRepository.markSent(secondAttempt.id(), secondAttempt.leaseToken(), "provider-msg-2", secondAttemptNow);

        assertThat(marked).isTrue();
        assertThat(statusFor(OWNER)).isEqualTo("SENT");
        assertThat(ambiguousFor(OWNER)).isTrue(); // still true -- the earlier ambiguous attempt is not erased
    }

    @Test
    void ambiguousOutcomeSurvivesManualReplay() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery claim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(
                claim.id(), claim.leaseToken(), "ambiguous permanent outcome", true, true, claim.attemptCount(), now);
        assertThat(statusFor(OWNER)).isEqualTo("PERMANENTLY_FAILED");
        assertThat(ambiguousFor(OWNER)).isTrue();

        assertThat(deliveryRepository.resetPermanentlyFailedForReplay(claim.id(), now.plusMinutes(1))).isTrue();

        assertThat(statusFor(OWNER)).isEqualTo("PENDING");
        assertThat(ambiguousFor(OWNER)).isTrue();
    }

    @Test
    void isLeaseCurrentDetectsAReclaimedLease() {
        // Backs the pre-send freshness check (#59, review fix): narrows, though it cannot fully close,
        // the window where a merely-paused (not dead) worker could otherwise resume its own send after
        // a second worker already reclaimed and completed the same row.
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery original = deliveryRepository.claimBatch(10, now).get(0);
        assertThat(deliveryRepository.isLeaseCurrent(original.id(), original.leaseToken(), now)).isTrue();

        // An expired-but-not-yet-reclaimed lease is no longer current and must not pass the
        // pre-send guard merely because its token is still stored on the row.
        assertThat(deliveryRepository.isLeaseCurrent(original.id(), original.leaseToken(), now.plusMinutes(11))).isFalse();

        OffsetDateTime muchLater = now.plusMinutes(30);
        ClaimedDelivery reclaimed = deliveryRepository.claimBatch(10, muchLater).get(0);

        assertThat(deliveryRepository.isLeaseCurrent(original.id(), original.leaseToken(), muchLater)).isFalse();
        assertThat(deliveryRepository.isLeaseCurrent(reclaimed.id(), reclaimed.leaseToken(), muchLater)).isTrue();
    }

    @Test
    void exhaustingAllSixAttemptsReachesPermanentlyFailed() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);
        assertThat(WeeklySummaryDeliveryRepository.MAX_ATTEMPTS).isEqualTo(6); // accepted B5 correction

        OffsetDateTime attemptNow = now;
        for (int attempt = 1; attempt <= WeeklySummaryDeliveryRepository.MAX_ATTEMPTS; attempt++) {
            ClaimedDelivery claim = deliveryRepository.claimBatch(10, attemptNow).get(0);
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            deliveryRepository.markFailed(claim.id(), claim.leaseToken(), "still failing", false, false, claim.attemptCount(), attemptNow);
            attemptNow = nextAttemptAtFor(OWNER) != null ? nextAttemptAtFor(OWNER) : attemptNow;
        }

        assertThat(statusFor(OWNER)).isEqualTo("PERMANENTLY_FAILED");
        // A terminal row is never picked up again, at any future time.
        assertThat(deliveryRepository.claimBatch(10, attemptNow.plusDays(30))).isEmpty();

        // Accepted B5: a manager can replay a terminal failure with a fresh attempt budget.
        boolean replayed = deliveryRepository.resetPermanentlyFailedForReplay(deliveryIdFor(OWNER), attemptNow);
        assertThat(replayed).isTrue();
        assertThat(statusFor(OWNER)).isEqualTo("PENDING");
        ClaimedDelivery replayedClaim = deliveryRepository.claimBatch(10, attemptNow).get(0);
        assertThat(replayedClaim.attemptCount()).isEqualTo(1);
    }

    @Test
    void permanentFailureSkipsBackoffOnTheFirstAttempt() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery claim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(claim.id(), claim.leaseToken(), "invalid recipient", true, false, claim.attemptCount(), now);

        assertThat(statusFor(OWNER)).isEqualTo("PERMANENTLY_FAILED");
    }

    // -- Eligibility revalidation before send (#59, review fix) --

    @Test
    void optOutDuringBackoffCancelsTheRetryInsteadOfSendingAStaleEmail() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);
        ClaimedDelivery claim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(claim.id(), claim.leaseToken(), "transient", false, false, claim.attemptCount(), now);
        assertThat(statusFor(OWNER)).isEqualTo("FAILED");

        optOutService.setOptedOut(workspace, project, OWNER, true);

        OffsetDateTime retryNow = now.plusMinutes(1);
        clock.setInstant(retryNow.toInstant());
        dispatchService.dispatchDue();

        assertThat(statusFor(OWNER)).isEqualTo("CANCELLED");
        assertThat(emailSender.sent().stream().noneMatch(sent -> "owner@example.com".equals(sent.toAddress()))).isTrue();
    }

    @Test
    void roleDowngradeDuringBackoffCancelsTheRetryInsteadOfSendingAStaleEmail() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);
        ClaimedDelivery claim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markFailed(claim.id(), claim.leaseToken(), "transient", false, false, claim.attemptCount(), now);
        assertThat(statusFor(OWNER)).isEqualTo("FAILED");

        // The workspace still needs an OWNER, so demote via a second owner rather than deleting the row.
        insertMember("user-second-owner", "OWNER", "second-owner@example.com");
        db.sql("UPDATE workspace_members SET role = 'MEMBER' WHERE workspace_id = :w AND subject_id = :s")
                .param("w", workspace).param("s", OWNER).update();

        OffsetDateTime retryNow = now.plusMinutes(1);
        clock.setInstant(retryNow.toInstant());
        dispatchService.dispatchDue();

        assertThat(statusFor(OWNER)).isEqualTo("CANCELLED");
        assertThat(emailSender.sent().stream().noneMatch(sent -> "owner@example.com".equals(sent.toAddress()))).isTrue();
    }

    // -- Opt-out cascade on membership removal (#59, review fix) --

    @Test
    void removingAMembershipCascadesTheirOptOutRow() {
        optOutService.setOptedOut(workspace, project, ADMIN, true);
        assertThat(optOutService.isOptedOut(workspace, project, ADMIN)).isTrue();

        db.sql("DELETE FROM workspace_members WHERE workspace_id = :w AND subject_id = :s")
                .param("w", workspace).param("s", ADMIN).update();

        Long remaining = db.sql("SELECT COUNT(*) FROM weekly_summary_opt_outs WHERE workspace_id = :w AND subject_id = :s")
                .param("w", workspace).param("s", ADMIN)
                .query(Long.class).single();
        assertThat(remaining).isZero();
    }

    // -- Missing verified email (accepted B3 correction) --

    @Test
    void recipientWithNoVerifiedEmailGetsAnAuditableBlockedRowInsteadOfBeingSkipped() {
        insertMemberWithNoEmail("user-no-email", "ADMIN");

        dispatchService.dispatchDue();

        assertThat(rowCount("user-no-email")).isEqualTo(1);
        assertThat(statusFor("user-no-email")).isEqualTo("BLOCKED_MISSING_EMAIL");
        // Never claimable -- it has no email to send to.
        assertThat(emailSender.sent().stream().anyMatch(sent -> sent.toAddress() == null)).isFalse();
    }

    @Test
    void replayEndpointRejectsBlockedMissingEmailUntilAVerifiedEmailIsCaptured() throws Exception {
        insertMemberWithNoEmail("user-no-email", "ADMIN");
        dispatchService.dispatchDue();
        UUID deliveryId = deliveryIdFor("user-no-email");
        assertThat(statusFor("user-no-email")).isEqualTo("BLOCKED_MISSING_EMAIL");

        mockMvc.perform(post(replayPath(deliveryId)).with(token(OWNER))).andExpect(status().isConflict());

        db.sql("UPDATE workspace_members SET email = :e WHERE workspace_id = :w AND subject_id = :s")
                .param("e", "now-verified@example.com").param("w", workspace).param("s", "user-no-email")
                .update();

        mockMvc.perform(post(replayPath(deliveryId)).with(token(OWNER))).andExpect(status().isOk());
        assertThat(statusFor("user-no-email")).isEqualTo("PENDING");
        assertThat(db.sql("SELECT recipient_email FROM weekly_summary_deliveries WHERE id = :id")
                        .param("id", deliveryId).query(String.class).single())
                .isEqualTo("now-verified@example.com");
    }

    // -- Retention cleanup (accepted B7 correction: 400 days, terminal rows only) --

    @Test
    void retentionCleanupDeletesOldTerminalRowsButNeverActiveOnes() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);
        deliveryRepository.createIfAbsent(workspace, project, ADMIN, "admin@example.com", DUE_WEEK_START, now);
        ClaimedDelivery ownerClaim = deliveryRepository.claimBatch(10, now).get(0);
        deliveryRepository.markSent(ownerClaim.id(), ownerClaim.leaseToken(), "msg-1", now);
        // ADMIN's row stays PENDING (never claimed) -- an old-but-active row.

        OffsetDateTime old = now.minusDays(401);
        db.sql("UPDATE weekly_summary_deliveries SET created_at = :old WHERE project_id = :p")
                .param("old", old).param("p", project).update();

        int deleted = deliveryRepository.deleteExpiredTerminal(now.minusDays(400));

        assertThat(deleted).isEqualTo(1);
        assertThat(rowCount(OWNER)).isZero(); // SENT + old: deleted
        assertThat(rowCount(ADMIN)).isEqualTo(1); // PENDING, however old: never touched
    }

    // -- Concurrency / lease fencing (mirrors BillingLedgerConcurrencyAndIsolationIntegrationTests) --

    @Test
    void staleLeaseCannotOverwriteANewerOutcome() {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery staleClaim = deliveryRepository.claimBatch(10, now).get(0);
        // A second worker reclaiming this row's lease directly (a real second claimBatch call would
        // correctly find nothing while lease_until is unexpired -- this directly proves the token
        // fencing that guarantee rests on).
        db.sql("UPDATE weekly_summary_deliveries SET lease_token = :newToken WHERE id = :id")
                .param("newToken", UUID.randomUUID()).param("id", staleClaim.id()).update();

        boolean staleMarkSent = deliveryRepository.markSent(staleClaim.id(), staleClaim.leaseToken(), "stale-msg", now);
        assertThat(staleMarkSent).isFalse();
    }

    @Test
    void expiredLeaseIsReclaimedByASubsequentClaim() {
        // A worker that claimed a row and then died/restarted before recording an outcome must not
        // hold the row forever -- an expired SENDING lease is itself reclaimable (accepted B5).
        OffsetDateTime now = OffsetDateTime.parse("2026-03-09T09:00:00Z");
        deliveryRepository.createIfAbsent(workspace, project, OWNER, "owner@example.com", DUE_WEEK_START, now);

        ClaimedDelivery deadWorkerClaim = deliveryRepository.claimBatch(10, now).get(0);
        assertThat(deadWorkerClaim.attemptCount()).isEqualTo(1);

        // Still within the lease window: not reclaimable yet.
        assertThat(deliveryRepository.claimBatch(10, now.plusMinutes(1))).isEmpty();

        // Well past any reasonable lease duration: reclaimable, and this is a fresh attempt.
        OffsetDateTime muchLater = now.plusMinutes(30);
        ClaimedDelivery reclaimed = deliveryRepository.claimBatch(10, muchLater).get(0);
        assertThat(reclaimed.id()).isEqualTo(deadWorkerClaim.id());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.leaseToken()).isNotEqualTo(deadWorkerClaim.leaseToken());

        // The dead worker's stale token can no longer conclude this delivery.
        assertThat(deliveryRepository.markSent(deadWorkerClaim.id(), deadWorkerClaim.leaseToken(), "stale", muchLater)).isFalse();
        assertThat(deliveryRepository.markSent(reclaimed.id(), reclaimed.leaseToken(), "fresh", muchLater)).isTrue();
        assertThat(statusFor(OWNER)).isEqualTo("SENT");
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

    private UUID deliveryIdFor(String subject) {
        return db.sql("SELECT id FROM weekly_summary_deliveries WHERE project_id = :p AND recipient_subject_id = :s")
                .param("p", project).param("s", subject)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .list()
                .get(0);
    }

    private boolean ambiguousFor(String subject) {
        return db.sql("SELECT last_outcome_ambiguous FROM weekly_summary_deliveries WHERE project_id = :p AND recipient_subject_id = :s")
                .param("p", project).param("s", subject)
                .query(Boolean.class).single();
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

    private String replayPath(UUID deliveryId) {
        return "/api/workspaces/" + workspace + "/projects/" + project + "/notifications/weekly-summary/deliveries/" + deliveryId
                + "/replay";
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
