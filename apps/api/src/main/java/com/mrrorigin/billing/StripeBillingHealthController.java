package com.mrrorigin.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Workspace-scoped Stripe billing-data health, diagnostics, and bounded recovery (#15). Reads
 * ({@code health}, {@code failed}) require workspace membership; mutations that replay events or
 * resume a backfill require manager permission, matching {@link StripeConnectionController}'s
 * existing convention for connection-affecting operations.
 */
@RestController
public class StripeBillingHealthController {

    private static final int DEFAULT_FAILED_EVENTS_LIMIT = 50;
    private static final int DEFAULT_MAX_REPLAY_EVENTS = 25;
    private static final int MAX_REPLAY_EVENTS = 100;
    private static final int DEFAULT_MAX_BACKFILL_PAGES = 25;
    private static final int MAX_BACKFILL_PAGES = 100;

    private final WorkspaceContext workspaceContext;
    private final StripeBillingHealthService healthService;
    private final StripeWebhookReplayService replayService;
    private final StripeBackfillService backfillService;
    private final StripeConnectionRepository connections;

    StripeBillingHealthController(
            WorkspaceContext workspaceContext,
            StripeBillingHealthService healthService,
            StripeWebhookReplayService replayService,
            StripeBackfillService backfillService,
            StripeConnectionRepository connections) {
        this.workspaceContext = workspaceContext;
        this.healthService = healthService;
        this.replayService = replayService;
        this.backfillService = backfillService;
        this.connections = connections;
    }

    @GetMapping("/api/workspaces/{workspaceId}/stripe-connection/health")
    public StripeBillingHealthService.StripeBillingHealthReport health(@PathVariable UUID workspaceId) {
        workspaceContext.requireMembership(workspaceId);
        return healthService.health(workspaceId);
    }

    @GetMapping("/api/workspaces/{workspaceId}/stripe-webhook-events/failed")
    public List<StripeBillingHealthService.FailedEventDiagnostic> failedEvents(
            @PathVariable UUID workspaceId, @RequestParam(required = false) Integer limit) {
        workspaceContext.requireMembership(workspaceId);
        return healthService.failedEvents(workspaceId, limit == null ? DEFAULT_FAILED_EVENTS_LIMIT : limit);
    }

    @PostMapping("/api/workspaces/{workspaceId}/stripe-webhook-events/{eventId}/replay")
    public ReplayResponse replayEvent(@PathVariable UUID workspaceId, @PathVariable UUID eventId) {
        workspaceContext.requireManager(workspaceId);
        StripeWebhookReplayService.ReplayOutcome outcome = replayService.replayEvent(workspaceId, eventId);
        return new ReplayResponse(eventId, outcome.name());
    }

    @PostMapping("/api/workspaces/{workspaceId}/stripe-webhook-events/replay-failed")
    public BatchReplayResponse replayFailed(
            @PathVariable UUID workspaceId, @RequestParam(required = false) Integer maxEvents) {
        workspaceContext.requireManager(workspaceId);
        int bounded = boundedOrDefault(maxEvents, DEFAULT_MAX_REPLAY_EVENTS, MAX_REPLAY_EVENTS, "maxEvents");
        StripeWebhookReplayService.BatchReplayOutcome outcome = replayService.replayFailed(workspaceId, bounded);
        return new BatchReplayResponse(outcome.count(), outcome.replayedEventIds());
    }

    @PostMapping("/api/workspaces/{workspaceId}/stripe-connection/backfill/resume")
    public BackfillResumeResponse resumeBackfill(
            @PathVariable UUID workspaceId, @RequestParam(required = false) Integer maxPages) {
        workspaceContext.requireManager(workspaceId);
        int bounded = boundedOrDefault(maxPages, DEFAULT_MAX_BACKFILL_PAGES, MAX_BACKFILL_PAGES, "maxPages");
        UUID connectionId = connections
                .findByWorkspaceId(workspaceId)
                .map(StripeConnection::id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Stripe connection for this workspace"));
        try {
            StripeBackfillService.BackfillRunOutcome outcome = backfillService.runBatch(connectionId, bounded);
            return new BackfillResumeResponse(
                    outcome.pagesProcessed(), outcome.phase().name(), outcome.complete(), outcome.connectionEligible());
        } catch (StripeBackfillIneligibleConnectionException ineligible) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ineligible.getMessage());
        } catch (StripeBackfillException upstreamFailure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe backfill request failed");
        }
    }

    private static int boundedOrDefault(Integer requested, int defaultValue, int max, String paramName) {
        if (requested == null) {
            return defaultValue;
        }
        if (requested < 1 || requested > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, paramName + " must be between 1 and " + max);
        }
        return requested;
    }

    public record ReplayResponse(UUID eventId, String outcome) {}

    public record BatchReplayResponse(int replayedCount, List<UUID> replayedEventIds) {}

    public record BackfillResumeResponse(int pagesProcessed, String phase, boolean complete, boolean connectionEligible) {}
}
