package com.mrrorigin.reporting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * #25's 30/60/90-day retained-MRR cohort read model: the acquisition-month heatmap behind the
 * Retention screen, and the date-range summary that feeds authoritative Retained MRR / NRR into
 * #23's Sources comparison. Any workspace member may read these.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention")
public class RetentionCohortController {

    private static final Set<String> DIMENSIONS = Set.of("SOURCE", "CAMPAIGN", "LANDING_PAGE");
    private static final int DEFAULT_AGE_DAYS = 30;

    private final RetentionCohortService cohorts;
    private final WorkspaceContext workspaceContext;

    public RetentionCohortController(RetentionCohortService cohorts, WorkspaceContext workspaceContext) {
        this.cohorts = cohorts;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping("/cohorts")
    public CohortsResponse cohorts(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam String dimension,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String campaign,
            @RequestParam(required = false, defaultValue = "false") boolean campaignMissing) {
        workspaceContext.requireMembership(workspaceId);
        RetentionCohortService.Dimension parsed = parseDimension(dimension);
        List<RetentionCohortService.CohortRow> rows =
                cohorts.heatmap(workspaceId, projectId, parsed, source, campaign, campaignMissing);
        return new CohortsResponse(workspaceId, projectId, dimension, source, campaign, campaignMissing, rows);
    }

    @GetMapping("/summary")
    public SummaryResponse summary(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam String dimension,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String campaign,
            @RequestParam(required = false, defaultValue = "false") boolean campaignMissing,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_AGE_DAYS) int ageDays) {
        workspaceContext.requireMembership(workspaceId);
        RetentionCohortService.Dimension parsed = parseDimension(dimension);
        List<RetentionCohortService.SummaryRow> rows = cohorts.summary(
                workspaceId, projectId, from, to, parsed, source, campaign, campaignMissing, ageDays);
        return new SummaryResponse(workspaceId, projectId, from, to, dimension, source, campaign, campaignMissing, ageDays, rows);
    }

    private static RetentionCohortService.Dimension parseDimension(String dimension) {
        if (!DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("dimension must be one of " + DIMENSIONS);
        }
        return RetentionCohortService.Dimension.valueOf(dimension);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }

    public record CohortsResponse(
            UUID workspaceId,
            UUID projectId,
            String dimension,
            String source,
            String campaign,
            boolean campaignMissing,
            List<RetentionCohortService.CohortRow> cohorts) {}

    public record SummaryResponse(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            String dimension,
            String source,
            String campaign,
            boolean campaignMissing,
            int ageDays,
            List<RetentionCohortService.SummaryRow> rows) {}
}
