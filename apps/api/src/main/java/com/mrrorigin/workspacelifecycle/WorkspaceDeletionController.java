package com.mrrorigin.workspacelifecycle;

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
 * Owner-only, cross-module workspace deletion (#62). Every endpoint requires {@link
 * WorkspaceContext#requireOwner}, a stricter bar than the manager-level check the rest of the API
 * uses -- and, deliberately, one that is never itself gated on the workspace being {@code DELETING},
 * so this flow keeps working for the duration of its own run.
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
        workspaceContext.requireOwner(workspaceId);
        return deletion.status(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No deletion request for this workspace"));
    }

    /**
     * Requires the exact confirmation string {@code "DELETE <workspaceId>"}. Idempotent: a retry with
     * the same (correct) confirmation against a workspace that already has a request returns that
     * request's current progress instead of starting duplicate work.
     */
    @PostMapping
    public WorkspaceDeletionRequestService.DeletionRunOutcome create(
            @PathVariable UUID workspaceId, @RequestBody(required = false) DeletionRequestBody body) {
        workspaceContext.requireOwner(workspaceId);
        return deletion.createOrGetRequest(workspaceId, body == null ? null : body.confirmation());
    }

    @PostMapping("/run")
    public WorkspaceDeletionRequestService.DeletionRunOutcome run(
            @PathVariable UUID workspaceId, @RequestParam(required = false) Integer maxRows) {
        workspaceContext.requireOwner(workspaceId);
        return deletion.runBatch(workspaceId, boundedOrDefault(maxRows));
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
