package com.mrrorigin.tracking;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.workspace.WorkspaceContext;

/** Authenticated, project-scoped, resumable full tracking-data deletion (#8). Mutations require manager permission. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tracking/deletion")
public class ProjectDataDeletionController {

    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int MAX_MAX_ROWS = 5000;

    private final WorkspaceContext workspaceContext;
    private final TrackingProjectAccess projectAccess;
    private final ProjectDataDeletionService deletion;

    public ProjectDataDeletionController(
            WorkspaceContext workspaceContext, TrackingProjectAccess projectAccess, ProjectDataDeletionService deletion) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.deletion = deletion;
    }

    @GetMapping
    public ProjectDataDeletionService.DeletionRunOutcome status(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return deletion.status(workspaceId, projectId)
                .orElseThrow(() -> new TrackingManagementException(
                        HttpStatus.NOT_FOUND, "deletion_run_not_found", "No deletion run has been started for this project"));
    }

    @PostMapping("/run")
    public ProjectDataDeletionService.DeletionRunOutcome run(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @RequestParam(required = false) Integer maxRows) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return deletion.runBatch(workspaceId, projectId, boundedOrDefault(maxRows));
    }

    @PostMapping("/restart")
    public ProjectDataDeletionService.DeletionRunOutcome restart(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        try {
            deletion.restart(workspaceId, projectId);
        } catch (IllegalStateException stillRunning) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, stillRunning.getMessage());
        }
        return deletion.status(workspaceId, projectId).orElseThrow();
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

    @ExceptionHandler(TrackingManagementException.class)
    ResponseEntity<Map<String, String>> managementError(TrackingManagementException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
