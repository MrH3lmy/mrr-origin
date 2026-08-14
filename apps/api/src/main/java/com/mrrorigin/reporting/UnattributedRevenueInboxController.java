package com.mrrorigin.reporting;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * #20's read-only unattributed-revenue inbox: "Stripe customer -> New MRR -> reason -> deterministic
 * repair action" per ARCHITECTURE.md. Any workspace member may read it; repairing a link is a
 * separate, manager-gated call ({@code CustomerLinkRepairController}).
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue")
public class UnattributedRevenueInboxController {

    private final UnattributedRevenueInboxService service;
    private final WorkspaceContext workspaceContext;

    public UnattributedRevenueInboxController(UnattributedRevenueInboxService service, WorkspaceContext workspaceContext) {
        this.service = service;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping
    public InboxPageResponse list(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(UnattributedRevenueInboxService.MAX_LIMIT) Integer limit) {
        workspaceContext.requireMembership(workspaceId);
        return InboxPageResponse.from(service.list(workspaceId, projectId, cursor, limit));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }

    public record InboxPageResponse(java.util.List<EntryResponse> entries, String nextCursor) {
        static InboxPageResponse from(UnattributedRevenueInboxService.Page page) {
            return new InboxPageResponse(
                    page.entries().stream().map(EntryResponse::from).toList(), page.nextCursor());
        }
    }

    public record EntryResponse(
            String stripeCustomerId,
            UUID acquisitionMovementId,
            OffsetDateTime acquisitionEffectiveAt,
            String currency,
            long amountMinor,
            String reason,
            String modelVersion,
            SuggestionResponse suggestion,
            String suggestionUnavailableReason) {
        static EntryResponse from(UnattributedRevenueInboxService.Entry entry) {
            return new EntryResponse(
                    entry.stripeCustomerId(),
                    entry.acquisitionMovementId(),
                    entry.acquisitionEffectiveAt(),
                    entry.currency(),
                    entry.amountMinor(),
                    entry.reason(),
                    entry.modelVersion(),
                    entry.suggestion() == null ? null : SuggestionResponse.from(entry.suggestion()),
                    entry.suggestionUnavailableReason());
        }
    }

    public record SuggestionResponse(UUID externalIdentityId, String externalUserId, UUID evidenceLinkId) {
        static SuggestionResponse from(UnattributedRevenueInboxService.Suggestion suggestion) {
            return new SuggestionResponse(
                    suggestion.externalIdentityId(), suggestion.externalUserId(), suggestion.evidenceLinkId());
        }
    }
}
