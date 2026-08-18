package com.mrrorigin.workspace;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated, owner-only, resumable full workspace deletion (#62). Both {@link #confirm} and
 * {@link #run} require the caller to be the workspace {@link WorkspaceRole#OWNER}, but neither uses
 * {@link WorkspaceContext#requireOwner} directly -- both check that role against {@link
 * WorkspaceContext#requireMembership} instead, via {@link #requireOwnerEvenWhileDeleting}. By the
 * time a caller repeats either call (an idempotent re-confirm, or continuing an in-progress run),
 * the workspace is already {@code DELETING}, and {@code requireOwner} would reject every other
 * mutation in that state, including these two. The deletion flow authorizing itself is exactly the
 * reason it must not be blocked by its own state transition.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/deletion")
public class WorkspaceDeletionController {

    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int MAX_MAX_ROWS = 5000;

    private final WorkspaceContext workspaceContext;
    private final WorkspaceDataDeletionService deletion;

    public WorkspaceDeletionController(WorkspaceContext workspaceContext, WorkspaceDataDeletionService deletion) {
        this.workspaceContext = workspaceContext;
        this.deletion = deletion;
    }

    @GetMapping
    public WorkspaceDataDeletionService.DeletionRunOutcome status(@PathVariable UUID workspaceId) {
        workspaceContext.requireMembership(workspaceId);
        return deletion.status(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No deletion has been confirmed for this workspace"));
    }

    /**
     * Starts the deletion, or -- idempotently -- reports the already-started run if one exists. Uses
     * the same DELETING-state bypass as {@link #run}: a repeat confirm call for a workspace that's
     * already {@code DELETING} (e.g. a retried request) must still succeed, so this cannot go through
     * {@link WorkspaceContext#requireOwner}.
     */
    @PostMapping("/confirm")
    public WorkspaceDataDeletionService.ConfirmationOutcome confirm(
            @PathVariable UUID workspaceId, @Valid @RequestBody ConfirmDeletionRequest request) {
        requireOwnerEvenWhileDeleting(workspaceId);
        return deletion.confirm(workspaceId, workspaceContext.subjectId(), request.confirmationSlug());
    }

    /** Processes one bounded batch of the current phase. Call repeatedly until {@code complete}. */
    @PostMapping("/run")
    public WorkspaceDataDeletionService.DeletionRunOutcome run(
            @PathVariable UUID workspaceId, @RequestParam(required = false) Integer maxRows) {
        requireOwnerEvenWhileDeleting(workspaceId);
        try {
            return deletion.runBatch(workspaceId, boundedOrDefault(maxRows));
        } catch (IllegalStateException notConfirmed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, notConfirmed.getMessage());
        }
    }

    private void requireOwnerEvenWhileDeleting(UUID workspaceId) {
        WorkspaceMember membership = workspaceContext.requireMembership(workspaceId);
        if (membership.role() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace owner permission required");
        }
    }

    private static int boundedOrDefault(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_ROWS;
        }
        if (requested < 1 || requested > MAX_MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxRows must be between 1 and " + MAX_MAX_ROWS);
        }
        return requested;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }

    public record ConfirmDeletionRequest(@NotBlank String confirmationSlug) {}
}
