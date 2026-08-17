package com.mrrorigin.notification;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.notification.EmailSender.EmailMessage;
import com.mrrorigin.notification.EmailSender.EmailSendException;
import com.mrrorigin.notification.EmailSender.EmailSendResult;
import com.mrrorigin.notification.WeeklySummaryDeliveryRepository.ClaimedDelivery;
import com.mrrorigin.notification.WeeklySummaryDeliveryRepository.DeliveryRef;
import com.mrrorigin.notification.WeeklySummaryService.WeeklySummaryResponse;
import com.mrrorigin.workspace.WorkspaceManagementService;
import com.mrrorigin.workspace.WorkspaceManagementService.SchedulableProject;
import com.mrrorigin.workspace.WorkspaceManagementService.WeeklySummaryRecipient;

/**
 * In-process scheduled tick for #59's weekly summary delivery (plan §1). Horizontal safety across
 * multiple API instances comes entirely from the DB-backed idempotency/lease design in {@link
 * WeeklySummaryDeliveryRepository} -- this tick itself needs no distributed lock, since every write it
 * makes is a conditional, uniquely-constrained INSERT/UPDATE that only one instance's statement can
 * win, exactly as {@code ARCHITECTURE.md}'s reliability rules prescribe.
 *
 * <p>Each dispatch tick does two independent things: (1) for every project whose accepted delivery
 * instant (Monday 08:00 project-local, recomputed fresh from the project's IANA zone every tick --
 * never a stored UTC instant, so DST is handled automatically) has passed for the most recently
 * completed week, idempotently create one delivery row per eligible recipient -- {@code PENDING} with
 * their verified email, or an auditable {@code BLOCKED_MISSING_EMAIL} row if they don't have one yet
 * (accepted B3); (2) claim and send a bounded batch of due (PENDING/FAILED past backoff, or an
 * expired SENDING lease) deliveries. A separate daily tick enforces the 400-day terminal-row
 * retention window (accepted B7).
 */
@Service
class WeeklySummaryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WeeklySummaryDispatchService.class);
    private static final int PROJECT_PAGE_SIZE = 500;
    private static final int MAX_SEND_BATCH_SIZE = 50;

    /** Accepted B7 correction: terminal delivery rows are retained 400 days, not for workspace lifetime. */
    private static final long RETENTION_DAYS = 400;

    private final WorkspaceManagementService workspaceManagementService;
    private final WeeklySummaryOptOutService optOutService;
    private final WeeklySummaryDeliveryRepository deliveryRepository;
    private final WeeklySummaryService weeklySummaryService;
    private final EmailSender emailSender;
    private final EmailProperties emailProperties;
    private final Clock clock;
    private final AtomicBoolean warnedNotConfigured = new AtomicBoolean(false);

    WeeklySummaryDispatchService(
            WorkspaceManagementService workspaceManagementService,
            WeeklySummaryOptOutService optOutService,
            WeeklySummaryDeliveryRepository deliveryRepository,
            WeeklySummaryService weeklySummaryService,
            EmailSender emailSender,
            EmailProperties emailProperties,
            Clock clock) {
        this.workspaceManagementService = workspaceManagementService;
        this.optOutService = optOutService;
        this.deliveryRepository = deliveryRepository;
        this.weeklySummaryService = weeklySummaryService;
        this.emailSender = emailSender;
        this.emailProperties = emailProperties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${mrrorigin.notification.dispatch.fixed-delay:PT5M}",
            initialDelayString = "${mrrorigin.notification.dispatch.initial-delay:PT1M}")
    void tick() {
        if (!emailProperties.isConfigured()) {
            if (warnedNotConfigured.compareAndSet(false, true)) {
                log.warn("Weekly summary email is not configured (POSTMARK_SERVER_TOKEN/WEEKLY_SUMMARY_SENDER_ADDRESS/"
                        + "WEB_APP_BASE_URL); dispatch is skipped until configured.");
            }
            return;
        }
        dispatchDue();
    }

    /**
     * Daily terminal-row retention sweep (#59, accepted B7 correction, plan §6) -- independent of
     * whether email sending itself is configured, since it cleans up historic rows regardless.
     */
    @Scheduled(
            fixedDelayString = "${mrrorigin.notification.retention.fixed-delay:P1D}",
            initialDelayString = "${mrrorigin.notification.retention.initial-delay:PT10M}")
    void retentionTick() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
        int deleted = deliveryRepository.deleteExpiredTerminal(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} weekly summary delivery record(s) older than {} days.", deleted, RETENTION_DAYS);
        }
    }

    /** Package-visible so the manual send endpoint (#59, manager-triggered) and tests can call it directly. */
    void dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        createDueDeliveries(now);
        sendBatch(now);
    }

    /**
     * Walks every project via real keyset pagination (#59, review fix) -- unlike a fixed-count cap
     * over an always-unpaged {@code findAll()}, this reaches every project within the tick regardless
     * of how many exist, so nothing beyond an arbitrary count is ever permanently starved.
     */
    private void createDueDeliveries(OffsetDateTime now) {
        UUID afterProjectId = null;
        List<SchedulableProject> page;
        do {
            page = workspaceManagementService.listProjectsForSchedulingPage(afterProjectId, PROJECT_PAGE_SIZE);
            for (SchedulableProject project : page) {
                createDueDeliveriesForProject(project, now);
                afterProjectId = project.projectId();
            }
        } while (page.size() == PROJECT_PAGE_SIZE);
    }

    private void createDueDeliveriesForProject(SchedulableProject project, OffsetDateTime now) {
        ZoneId zone;
        try {
            zone = ZoneId.of(project.timezone());
        } catch (RuntimeException invalidZone) {
            log.warn("Project {} has an invalid timezone; skipping weekly summary dispatch.", project.projectId());
            return;
        }
        ZonedDateTime nowZoned = now.atZoneSameInstant(zone);
        LocalDate mondayThisWeek = nowZoned.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // Accepted B1: Monday 08:00 project-local delivers the week that ended at this Monday 00:00.
        ZonedDateTime deliveryInstant = mondayThisWeek.atTime(8, 0).atZone(zone);
        if (nowZoned.isBefore(deliveryInstant)) {
            return;
        }
        createDeliveriesForProjectWeek(project.workspaceId(), project.projectId(), mondayThisWeek.minusWeeks(1), now);
    }

    private void createDeliveriesForProjectWeek(UUID workspaceId, UUID projectId, LocalDate weekStart, OffsetDateTime now) {
        List<WeeklySummaryRecipient> recipients = workspaceManagementService.listWeeklySummaryRecipients(workspaceId);
        if (recipients.isEmpty()) {
            return;
        }
        Set<String> optedOut = optOutService.optedOutSubjectIds(workspaceId, projectId);
        for (WeeklySummaryRecipient recipient : recipients) {
            if (optedOut.contains(recipient.subjectId())) {
                continue;
            }
            // recipient.email() may be null here -- accepted B3: recorded as an auditable
            // BLOCKED_MISSING_EMAIL row rather than silently skipped (see createIfAbsent).
            deliveryRepository.createIfAbsent(workspaceId, projectId, recipient.subjectId(), recipient.email(), weekStart, now);
        }
    }

    private void sendBatch(OffsetDateTime now) {
        List<ClaimedDelivery> claimed = deliveryRepository.claimBatch(MAX_SEND_BATCH_SIZE, now);
        for (ClaimedDelivery delivery : claimed) {
            sendOne(delivery, now);
        }
    }

    /**
     * Manager-triggered immediate dispatch for one project's current completed week (#59, plan §1a) --
     * bypasses the Monday 08:00 gate but reuses the exact same idempotency key as the scheduled tick,
     * so it can never double-send against a tick that already ran for the same (project, recipient,
     * week). Only sends this project's own deliveries, never touching other projects' due batch.
     */
    void dispatchProjectNow(UUID workspaceId, UUID projectId, String timezone) {
        if (!emailProperties.isConfigured()) {
            // Review fix: this path used to bypass tick()'s isConfigured() guard entirely, so a blank
            // token/sender/base-URL deployment could still create and claim rows (and record
            // failures) while returning HTTP 200. Reject before any work is created/claimed.
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Weekly summary email is not configured");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime nowZoned = now.atZoneSameInstant(zone);
        LocalDate mondayThisWeek = nowZoned.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekStart = mondayThisWeek.minusWeeks(1);
        createDeliveriesForProjectWeek(workspaceId, projectId, weekStart, now);

        List<ClaimedDelivery> claimed = deliveryRepository.claimBatchForProject(projectId, MAX_SEND_BATCH_SIZE, now);
        for (ClaimedDelivery delivery : claimed) {
            sendOne(delivery, now);
        }
    }

    /**
     * Manager-triggered replay of a terminal delivery (#59, accepted B3/B5 corrections, plan §4d):
     * {@code PERMANENTLY_FAILED} gets a fresh attempt budget; {@code BLOCKED_MISSING_EMAIL} is
     * re-checked against the member's currently stored verified email and only replayed if one now
     * exists. Any other status is rejected -- replay is only meaningful for a terminal outcome.
     */
    void replay(UUID workspaceId, UUID projectId, UUID deliveryId) {
        DeliveryRef ref = deliveryRepository
                .findForReplay(workspaceId, projectId, deliveryId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Delivery not found"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean replayed =
                switch (ref.status()) {
                    case "PERMANENTLY_FAILED" -> deliveryRepository.resetPermanentlyFailedForReplay(deliveryId, now);
                    case "BLOCKED_MISSING_EMAIL" -> replayBlockedMissingEmail(ref, now);
                    default -> throw new ResponseStatusException(
                            CONFLICT, "Only a PERMANENTLY_FAILED or BLOCKED_MISSING_EMAIL delivery can be replayed");
                };
        if (!replayed) {
            throw new ResponseStatusException(CONFLICT, "Delivery could not be replayed; its state changed concurrently");
        }
    }

    private boolean replayBlockedMissingEmail(DeliveryRef ref, OffsetDateTime now) {
        String email = workspaceManagementService.currentVerifiedEmail(ref.workspaceId(), ref.recipientSubjectId());
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(CONFLICT, "This member still has no verified email captured");
        }
        return deliveryRepository.resolveBlockedMissingEmailForReplay(ref.id(), email, now);
    }

    private void sendOne(ClaimedDelivery delivery, OffsetDateTime now) {
        // Review fix: eligibility (opt-out, manager role) was previously only checked at row-creation
        // time -- a member who opts out, or is demoted/removed, while a row sits FAILED in backoff
        // would still receive later retries. Revalidate immediately before sending and cancel instead.
        if (!stillEligible(delivery)) {
            deliveryRepository.markCancelled(
                    delivery.id(), delivery.leaseToken(), "Recipient opted out or is no longer an eligible manager", now);
            return;
        }
        // Review fix: narrows (but, for a worker merely paused rather than dead, cannot fully close)
        // the window where an expired-lease reclaim races a still-running send from the original
        // worker -- see the delivery plan's "Delivery guarantee" for the corrected, honest scope of
        // this mitigation. Never claims two internal claim attempts can never both send.
        OffsetDateTime leaseCheckAt = OffsetDateTime.now(clock);
        if (!deliveryRepository.isLeaseCurrent(delivery.id(), delivery.leaseToken(), leaseCheckAt)) {
            log.warn("Weekly summary delivery {} lease is expired or was already reclaimed; not sending from this worker.", delivery.id());
            return;
        }
        try {
            EmailMessage message = renderMessage(delivery);
            EmailSendResult result = emailSender.send(message);
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            if (!deliveryRepository.markSent(delivery.id(), delivery.leaseToken(), result.providerMessageId(), completedAt)) {
                log.warn("Weekly summary delivery {} lease was lost before it could be marked SENT.", delivery.id());
            }
        } catch (EmailSendException sendFailure) {
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            deliveryRepository.markFailed(
                    delivery.id(), delivery.leaseToken(), sendFailure.getMessage(), sendFailure.permanent(),
                    sendFailure.ambiguous(), delivery.attemptCount(), completedAt);
        } catch (RuntimeException unexpected) {
            log.error("Unexpected failure rendering/sending weekly summary delivery {}", delivery.id(), unexpected);
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            deliveryRepository.markFailed(
                    delivery.id(), delivery.leaseToken(), unexpected.getMessage(), false, false, delivery.attemptCount(),
                    completedAt);
        }
    }

    /** Re-checks opt-out and manager role right now, not at whatever earlier time this row was created (#59, review fix). */
    private boolean stillEligible(ClaimedDelivery delivery) {
        if (optOutService.isOptedOut(delivery.workspaceId(), delivery.projectId(), delivery.recipientSubjectId())) {
            return false;
        }
        return workspaceManagementService.isCurrentWeeklySummaryRecipient(delivery.workspaceId(), delivery.recipientSubjectId());
    }

    private EmailMessage renderMessage(ClaimedDelivery delivery) {
        String timezone = workspaceManagementService.projectTimezoneForScheduling(delivery.workspaceId(), delivery.projectId());
        WeeklySummaryResponse summary =
                weeklySummaryService.summary(delivery.workspaceId(), delivery.projectId(), delivery.weekStart(), timezone);
        String projectName = workspaceManagementService.projectNameForScheduling(delivery.workspaceId(), delivery.projectId());
        String subject = "Weekly summary for " + projectName + " -- week of " + delivery.weekStart();
        String optOutUrl = optOutUrl(delivery.workspaceId(), delivery.projectId());
        String textBody = WeeklySummaryRenderer.renderText(summary) + "\n\n" + optOutText(optOutUrl);
        String htmlBody = WeeklySummaryRenderer.renderHtml(summary) + optOutHtml(optOutUrl);
        return new EmailMessage(
                delivery.recipientEmail(),
                emailProperties.senderAddress(),
                emailProperties.replyToAddress(),
                subject,
                textBody,
                htmlBody,
                delivery.id().toString());
    }

    /**
     * Accepted B4: every email must contain a direct link to the authenticated opt-out setting for
     * this project. Authenticated-only in v1 -- the link itself requires the recipient to be signed
     * in to act on it, per the accepted contract (no unauthenticated one-click unsubscribe).
     */
    private String optOutUrl(UUID workspaceId, UUID projectId) {
        return emailProperties.webBaseUrl() + "/app/" + workspaceId + "/projects/" + projectId + "/weekly-summary";
    }

    private static String optOutText(String optOutUrl) {
        return "To stop receiving this weekly summary for this project, sign in and update your "
                + "subscription: " + optOutUrl;
    }

    private static String optOutHtml(String optOutUrl) {
        return "<p>To stop receiving this weekly summary for this project, "
                + "<a href=\"" + optOutUrl + "\">sign in and update your subscription</a>.</p>";
    }
}
