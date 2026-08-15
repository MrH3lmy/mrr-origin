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

/**
 * #22's founder overview read models: period+project-scoped MRR movement totals, current MRR,
 * source highlights, and the movement-level drill-down that backs every summarized number. Any
 * workspace member may read these.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reporting")
public class RevenueOverviewController {

    private final RevenueOverviewService overviewService;
    private final RevenueMovementsService movementsService;
    private final WorkspaceContext workspaceContext;

    public RevenueOverviewController(
            RevenueOverviewService overviewService,
            RevenueMovementsService movementsService,
            WorkspaceContext workspaceContext) {
        this.overviewService = overviewService;
        this.movementsService = movementsService;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping("/overview")
    public OverviewResponse overview(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to) {
        workspaceContext.requireMembership(workspaceId);
        return OverviewResponse.from(overviewService.overview(workspaceId, projectId, from, to));
    }

    @GetMapping("/movements")
    public MovementsPageResponse movements(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(RevenueMovementsService.MAX_LIMIT) Integer limit) {
        workspaceContext.requireMembership(workspaceId);
        return MovementsPageResponse.from(movementsService.list(
                workspaceId, projectId, from, to, movementType, source, currency, cursor, limit));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }

    public record OverviewResponse(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            String calculationVersion,
            String modelVersion,
            List<RevenueOverviewService.MovementTotal> movementTotals,
            List<RevenueOverviewService.CurrentMrr> currentMrr,
            List<RevenueOverviewService.SourceHighlight> sourceHighlights) {
        static OverviewResponse from(RevenueOverviewService.Overview overview) {
            return new OverviewResponse(
                    overview.workspaceId(),
                    overview.projectId(),
                    overview.from(),
                    overview.to(),
                    overview.calculationVersion(),
                    overview.modelVersion(),
                    overview.movementTotals(),
                    overview.currentMrr(),
                    overview.sourceHighlights());
        }
    }

    public record MovementsPageResponse(List<RevenueMovementsService.Entry> entries, String nextCursor) {
        static MovementsPageResponse from(RevenueMovementsService.Page page) {
            return new MovementsPageResponse(page.entries(), page.nextCursor());
        }
    }
}
