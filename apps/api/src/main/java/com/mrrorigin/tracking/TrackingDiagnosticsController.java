package com.mrrorigin.tracking;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;

/** Authenticated, project-scoped installation diagnostics (#8). Read-only, so membership suffices. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tracking/diagnostics")
public class TrackingDiagnosticsController {

    private final WorkspaceContext workspaceContext;
    private final TrackingProjectAccess projectAccess;
    private final TrackingProjectDiagnosticsService diagnostics;

    public TrackingDiagnosticsController(
            WorkspaceContext workspaceContext, TrackingProjectAccess projectAccess, TrackingProjectDiagnosticsService diagnostics) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.diagnostics = diagnostics;
    }

    @GetMapping
    public TrackingProjectDiagnosticsService.ProjectDiagnosticsReport diagnostics(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return diagnostics.report(workspaceId, projectId);
    }

    @ExceptionHandler(TrackingManagementException.class)
    ResponseEntity<Map<String, String>> managementError(TrackingManagementException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
