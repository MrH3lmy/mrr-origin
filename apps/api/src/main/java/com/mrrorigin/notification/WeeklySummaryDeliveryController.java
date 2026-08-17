package com.mrrorigin.notification;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.notification.WeeklySummaryDeliveryRepository.DeliveryStatusRow;
import com.mrrorigin.workspace.WorkspaceContext;
import com.mrrorigin.workspace.WorkspaceManagementService;

/**
 * Manager-only manual trigger, replay, and delivery-status visibility for #59 (plan §1a/§4d),
 * matching the authorization precedent of every other batch-trigger endpoint ({@code
 * TrackingRetentionController#run}, {@code ProjectDataDeletionController}).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary")
public class WeeklySummaryDeliveryController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final WorkspaceContext workspaceContext;
    private final WorkspaceManagementService workspaceManagementService;
    private final WeeklySummaryDispatchService dispatchService;
    private final WeeklySummaryDeliveryRepository deliveryRepository;

    public WeeklySummaryDeliveryController(
            WorkspaceContext workspaceContext,
            WorkspaceManagementService workspaceManagementService,
            WeeklySummaryDispatchService dispatchService,
            WeeklySummaryDeliveryRepository deliveryRepository) {
        this.workspaceContext = workspaceContext;
        this.workspaceManagementService = workspaceManagementService;
        this.dispatchService = dispatchService;
        this.deliveryRepository = deliveryRepository;
    }

    @PostMapping("/send")
    public SendResponse send(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireManager(workspaceId);
        String timezone = workspaceManagementService.projectTimezone(workspaceId, projectId);
        dispatchService.dispatchProjectNow(workspaceId, projectId, timezone);
        return new SendResponse(OffsetDateTime.now());
    }

    /**
     * Manual replay of a terminal delivery (#59, accepted B3/B5 corrections, plan §4d):
     * {@code PERMANENTLY_FAILED} gets a fresh attempt budget; {@code BLOCKED_MISSING_EMAIL} is
     * replayed only if the member now has a verified email.
     */
    @PostMapping("/deliveries/{deliveryId}/replay")
    public SendResponse replay(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID deliveryId) {
        workspaceContext.requireManager(workspaceId);
        workspaceManagementService.getProject(workspaceId, projectId);
        dispatchService.replay(workspaceId, projectId, deliveryId);
        return new SendResponse(OffsetDateTime.now());
    }

    @GetMapping("/deliveries")
    public List<DeliveryResponse> deliveries(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @RequestParam(required = false) Integer limit) {
        workspaceContext.requireManager(workspaceId);
        workspaceManagementService.projectTimezone(workspaceId, projectId);
        int bounded = boundedOrDefault(limit);
        return deliveryRepository.listRecent(workspaceId, projectId, bounded).stream()
                .map(WeeklySummaryDeliveryController::toResponse)
                .toList();
    }

    private static int boundedOrDefault(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    private static DeliveryResponse toResponse(DeliveryStatusRow row) {
        return new DeliveryResponse(
                row.id(), row.recipientEmail(), row.weekStart(), row.status(), row.attemptCount(), row.lastError(),
                row.lastOutcomeAmbiguous(), row.providerMessageId(), row.createdAt(), row.updatedAt());
    }

    public record SendResponse(OffsetDateTime triggeredAt) {}

    public record DeliveryResponse(
            UUID id,
            String recipientEmail,
            LocalDate weekStart,
            String status,
            int attemptCount,
            String lastError,
            boolean lastOutcomeAmbiguous,
            String providerMessageId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
