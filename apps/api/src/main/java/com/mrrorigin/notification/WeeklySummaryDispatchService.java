package com.mrrorigin.notification;

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

import com.mrrorigin.notification.EmailSender.EmailMessage;
import com.mrrorigin.notification.EmailSender.EmailSendException;
import com.mrrorigin.notification.EmailSender.EmailSendResult;
import com.mrrorigin.notification.WeeklySummaryDeliveryRepository.ClaimedDelivery;
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
 * <p>Each tick does two independent things: (1) for every project whose accepted delivery instant
 * (Monday 08:00 project-local, recomputed fresh from the project's IANA zone every tick -- never a
 * stored UTC instant, so DST is handled automatically) has passed for the most recently completed
 * week, idempotently create one PENDING delivery row per eligible, non-opted-out recipient; (2) claim
 * and send a bounded batch of due (PENDING/FAILED, past their backoff) deliveries.
 */
@Service
class WeeklySummaryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WeeklySummaryDispatchService.class);
    private static final int MAX_PROJECTS_PER_TICK = 500;
    private static final int MAX_SEND_BATCH_SIZE = 50;

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
                log.warn("Weekly summary email is not configured (POSTMARK_SERVER_TOKEN/WEEKLY_SUMMARY_SENDER_ADDRESS); "
                        + "dispatch is skipped until configured.");
            }
            return;
        }
        dispatchDue();
    }

    /** Package-visible so the manual send endpoint (#59, manager-triggered) and tests can call it directly. */
    void dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        createDueDeliveries(now);
        sendBatch(now);
    }

    private void createDueDeliveries(OffsetDateTime now) {
        List<SchedulableProject> projects = workspaceManagementService.listAllProjectsForScheduling();
        int count = 0;
        for (SchedulableProject project : projects) {
            if (++count > MAX_PROJECTS_PER_TICK) {
                log.warn("More than {} projects; remaining projects will be picked up on the next tick.", MAX_PROJECTS_PER_TICK);
                break;
            }
            createDueDeliveriesForProject(project, now);
        }
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

    private void sendOne(ClaimedDelivery delivery, OffsetDateTime now) {
        try {
            EmailMessage message = renderMessage(delivery);
            EmailSendResult result = emailSender.send(message);
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            if (!deliveryRepository.markSent(delivery.id(), delivery.lastAttemptedAt(), result.providerMessageId(), completedAt)) {
                log.warn("Weekly summary delivery {} lease was lost before it could be marked SENT.", delivery.id());
            }
        } catch (EmailSendException sendFailure) {
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            deliveryRepository.markFailed(
                    delivery.id(), delivery.lastAttemptedAt(), sendFailure.getMessage(), sendFailure.permanent(),
                    delivery.attemptCount(), completedAt);
        } catch (RuntimeException unexpected) {
            log.error("Unexpected failure rendering/sending weekly summary delivery {}", delivery.id(), unexpected);
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            deliveryRepository.markFailed(
                    delivery.id(), delivery.lastAttemptedAt(), unexpected.getMessage(), false, delivery.attemptCount(), completedAt);
        }
    }

    private EmailMessage renderMessage(ClaimedDelivery delivery) {
        String timezone = workspaceManagementService.projectTimezoneForScheduling(delivery.workspaceId(), delivery.projectId());
        WeeklySummaryResponse summary =
                weeklySummaryService.summary(delivery.workspaceId(), delivery.projectId(), delivery.weekStart(), timezone);
        String projectName = workspaceManagementService.projectNameForScheduling(delivery.workspaceId(), delivery.projectId());
        String subject = "Weekly summary for " + projectName + " -- week of " + delivery.weekStart();
        return new EmailMessage(
                delivery.recipientEmail(),
                emailProperties.senderAddress(),
                emailProperties.replyToAddress(),
                subject,
                WeeklySummaryRenderer.renderText(summary),
                WeeklySummaryRenderer.renderHtml(summary));
    }
}
