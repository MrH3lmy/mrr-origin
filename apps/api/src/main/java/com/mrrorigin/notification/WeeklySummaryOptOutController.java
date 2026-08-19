package com.mrrorigin.notification;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;
import com.mrrorigin.workspace.WorkspaceManagementService;

/**
 * A member's own weekly-summary subscription for one project (#59, plan §3b). Authenticated-only:
 * every member manages their own opt-out, no manager privilege required, and no unauthenticated
 * unsubscribe link exists in v1 (accepted B4).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary/opt-out")
public class WeeklySummaryOptOutController {

    private final WorkspaceContext workspaceContext;
    private final WorkspaceManagementService workspaceManagementService;
    private final WeeklySummaryOptOutService optOutService;

    public WeeklySummaryOptOutController(
            WorkspaceContext workspaceContext,
            WorkspaceManagementService workspaceManagementService,
            WeeklySummaryOptOutService optOutService) {
        this.workspaceContext = workspaceContext;
        this.workspaceManagementService = workspaceManagementService;
        this.optOutService = optOutService;
    }

    @GetMapping
    public OptOutResponse getOptOut(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        String subjectId = requireProjectMembership(workspaceId, projectId);
        return new OptOutResponse(optOutService.isOptedOut(workspaceId, projectId, subjectId));
    }

    @PutMapping
    public OptOutResponse updateOptOut(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @RequestBody UpdateOptOutRequest request) {
        String subjectId = requireProjectMembership(workspaceId, projectId);
        workspaceContext.requireWritable(workspaceId);
        return new OptOutResponse(optOutService.setOptedOut(workspaceId, projectId, subjectId, request.optedOut()));
    }

    private String requireProjectMembership(UUID workspaceId, UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        workspaceManagementService.getProject(workspaceId, projectId);
        return workspaceContext.subjectId();
    }

    public record UpdateOptOutRequest(boolean optedOut) {}

    public record OptOutResponse(boolean optedOut) {}
}
