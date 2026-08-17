package com.mrrorigin.notification;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.notification.WeeklySummaryService.WeeklySummaryResponse;
import com.mrrorigin.workspace.WorkspaceContext;

/**
 * #26's weekly action summary, as JSON (the DTO exports/UI reconcile against), plain text, and HTML
 * (the email-ready presentations -- see {@code docs/weekly-summary-export-plan.md} §3 for why
 * this stops at the DTO/renderers and does not send anything). Mounted under {@code /reporting} like
 * every other read model in {@link com.mrrorigin.reporting.RevenueOverviewController} -- any
 * workspace member may read it.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reporting")
public class WeeklySummaryController {

    private final WeeklySummaryService weeklySummaryService;
    private final WorkspaceContext workspaceContext;

    public WeeklySummaryController(WeeklySummaryService weeklySummaryService, WorkspaceContext workspaceContext) {
        this.weeklySummaryService = weeklySummaryService;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping("/weekly-summary")
    public WeeklySummaryResponse weeklySummary(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) LocalDate weekStart) {
        workspaceContext.requireMembership(workspaceId);
        return weeklySummaryService.summary(workspaceId, projectId, weekStart);
    }

    @GetMapping(value = "/weekly-summary.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String weeklySummaryText(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) LocalDate weekStart) {
        workspaceContext.requireMembership(workspaceId);
        return WeeklySummaryRenderer.renderText(weeklySummaryService.summary(workspaceId, projectId, weekStart));
    }

    @GetMapping(value = "/weekly-summary.html", produces = MediaType.TEXT_HTML_VALUE)
    public String weeklySummaryHtml(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) LocalDate weekStart) {
        workspaceContext.requireMembership(workspaceId);
        return WeeklySummaryRenderer.renderHtml(weeklySummaryService.summary(workspaceId, projectId, weekStart));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }
}
