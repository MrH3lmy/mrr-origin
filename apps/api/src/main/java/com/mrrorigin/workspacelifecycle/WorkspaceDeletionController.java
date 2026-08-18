package com.mrrorigin.workspacelifecycle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Owner-only, cross-module workspace deletion (#62). Active and in-flight deletion endpoints require
 * {@link WorkspaceContext#requireOwner}, a stricter bar than the manager-level check the rest of the
 * API uses -- and, deliberately, one that is never itself gated on the workspace being {@code
 * DELETING}, so this flow keeps working for the duration of its own run.
 *
 * <p>After {@code WORKSPACE_ROOT} completes, the workspace and all membership rows are gone by
 * contract, so there is no owner row left against which {@code requireOwner} can authenticate a retry.
 * The surviving tombstone is deliberately forbidden from retaining a member subject id. Completed
 * requests therefore short-circuit to the tombstone's opaque terminal outcome ({@code DONE}, no
 * workspace/customer/member data) before membership authorization. This is what makes the required
 * post-completion status and {@code /run} retries idempotent without weakening authorization for any
 * live or in-flight workspace, or retaining identity data solely for authorization after deletion.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/deletion")
public class WorkspaceDeletionController {

    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int MAX_MAX_ROWS = 5000;

    private final WorkspaceContext workspaceContext;
    private final WorkspaceDeletionRequestService deletion;

    public WorkspaceDeletionController(WorkspaceContext workspaceContext, WorkspaceDeletionRequestService deletion) {
        this.workspaceContext = workspaceContext;
        this.deletion = deletion;
    }

    @GetMapping
    public WorkspaceDeletionRequestService.DeletionRunOutcome status(@PathVariable UUID workspaceId) {
        Optional<WorkspaceDeletionRequestService.DeletionRunOutcome> completed = authorizeOrCompleted(workspaceId);
        if (completed.isPresent()) {
            return completed.get();
        }
        return deletion.status(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No deletion request for this workspace"));
    }

    /**
     * Requires the exact confirmation string {@code "DELETE <workspaceId>"}. Idempotent: a retry with
     * the same (correct) confirmation against a workspace that already has a request returns that
     * request's current progress instead of starting duplicate work. The service still validates the
     * confirmation on a completed retry before returning its tombstone.
     */
    @PostMapping
    public WorkspaceDeletionRequestService.DeletionRunOutcome create(
            @PathVariable UUID workspaceId, @RequestBody(required = false) DeletionRequestBody body) {
        authorizeOrCompleted(workspaceId);
        return deletion.createOrGetRequest(workspaceId, body == null ? null : body.confirmation());
    }

    @PostMapping("/run")
    public WorkspaceDeletionRequestService.DeletionRunOutcome run(
            @PathVariable UUID workspaceId, @RequestParam(required = false) Integer maxRows) {
        Optional<WorkspaceDeletionRequestService.DeletionRunOutcome> completed = authorizeOrCompleted(workspaceId);
        if (completed.isPresent()) {
            return completed.get();
        }
        return deletion.runBatch(workspaceId, boundedOrDefault(maxRows));
    }

    /**
     * Returns the terminal tombstone when the workspace is already gone; otherwise authorizes the
     * caller as the live workspace owner. The second tombstone check closes the small race where the
     * root-delete transaction commits between the first check and {@code requireOwner}'s membership
     * lookup.
     */
    private Optional<WorkspaceDeletionRequestService.DeletionRunOutcome> authorizeOrCompleted(UUID workspaceId) {
        Optional<WorkspaceDeletionRequestService.DeletionRunOutcome> completed = completedOutcome(workspaceId);
        if (completed.isPresent()) {
            return completed;
        }
        try {
            workspaceContext.requireOwner(workspaceId);
            return Optional.empty();
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                completed = completedOutcome(workspaceId);
                if (completed.isPresent()) {
                    return completed;
                }
            }
            throw ex;
        }
    }

    private Optional<WorkspaceDeletionRequestService.DeletionRunOutcome> completedOutcome(UUID workspaceId) {
        return deletion.status(workspaceId).filter(WorkspaceDeletionRequestService.DeletionRunOutcome::complete);
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

    public record DeletionRequestBody(String confirmation) {}
}
