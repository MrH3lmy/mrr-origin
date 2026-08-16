package com.mrrorigin.reporting;

import java.time.OffsetDateTime;
import java.util.List;
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

/** #24's workspace/project-scoped customer directory: search and select before opening a timeline. */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/customers")
public class CustomerDirectoryController {

    private final CustomerDirectoryService directory;
    private final WorkspaceContext workspaceContext;

    public CustomerDirectoryController(CustomerDirectoryService directory, WorkspaceContext workspaceContext) {
        this.directory = directory;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping
    public PageResponse list(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(CustomerDirectoryService.MAX_LIMIT) Integer limit) {
        workspaceContext.requireMembership(workspaceId);
        return PageResponse.from(directory.list(workspaceId, projectId, search, cursor, limit));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }

    public record PageResponse(List<EntryResponse> entries, String nextCursor) {
        static PageResponse from(CustomerDirectoryService.Page page) {
            return new PageResponse(page.entries().stream().map(EntryResponse::from).toList(), page.nextCursor());
        }
    }

    public record EntryResponse(
            String stripeCustomerId,
            boolean deleted,
            OffsetDateTime providerCreatedAt,
            OffsetDateTime acquisitionEffectiveAt,
            String confidence,
            String unattributedReason,
            String firstSource,
            List<CustomerDirectoryService.CurrentMrrByCurrency> currentMrr,
            List<String> subscriptionStatuses) {
        static EntryResponse from(CustomerDirectoryService.Entry entry) {
            return new EntryResponse(
                    entry.stripeCustomerId(),
                    entry.deleted(),
                    entry.providerCreatedAt(),
                    entry.acquisitionEffectiveAt(),
                    entry.confidence(),
                    entry.unattributedReason(),
                    entry.firstSource(),
                    entry.currentMrr(),
                    entry.subscriptionStatuses());
        }
    }
}
