package com.mrrorigin.reporting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * source highlights, and the movement-level drill-down that backs every summarized number. Also
 * exposes #23's source/campaign/landing-page comparison, an extension of the same read models. Any
 * workspace member may read these.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reporting")
public class RevenueOverviewController {

    private static final Set<String> DIMENSIONS = Set.of("SOURCE", "CAMPAIGN", "LANDING_PAGE");
    private static final int DEFAULT_RETENTION_AGE_DAYS = 30;

    private final RevenueOverviewService overviewService;
    private final RevenueMovementsService movementsService;
    private final SourceComparisonService comparisonService;
    private final RetentionCohortService retentionCohortService;
    private final WorkspaceContext workspaceContext;

    public RevenueOverviewController(
            RevenueOverviewService overviewService,
            RevenueMovementsService movementsService,
            SourceComparisonService comparisonService,
            RetentionCohortService retentionCohortService,
            WorkspaceContext workspaceContext) {
        this.overviewService = overviewService;
        this.movementsService = movementsService;
        this.comparisonService = comparisonService;
        this.retentionCohortService = retentionCohortService;
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
            @RequestParam(required = false, defaultValue = "false") boolean sourceUnattributed,
            @RequestParam(required = false, defaultValue = "false") boolean sourceMissing,
            @RequestParam(required = false) String campaign,
            @RequestParam(required = false, defaultValue = "false") boolean campaignMissing,
            @RequestParam(required = false) String landingPage,
            @RequestParam(required = false, defaultValue = "false") boolean landingPageMissing,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(RevenueMovementsService.MAX_LIMIT) Integer limit) {
        workspaceContext.requireMembership(workspaceId);
        return MovementsPageResponse.from(movementsService.list(
                workspaceId, projectId, from, to, movementType, source, sourceUnattributed, sourceMissing, campaign,
                campaignMissing, landingPage, landingPageMissing, currency, cursor, limit));
    }

    @GetMapping("/comparison")
    public ComparisonResponse comparison(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam String dimension,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String campaign,
            @RequestParam(required = false, defaultValue = "false") boolean campaignMissing,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_RETENTION_AGE_DAYS) int retentionAgeDays) {
        workspaceContext.requireMembership(workspaceId);
        if (!DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("dimension must be one of " + DIMENSIONS);
        }
        SourceComparisonService.Dimension parsed = SourceComparisonService.Dimension.valueOf(dimension);
        List<SourceComparisonService.ComparisonRow> rows =
                comparisonService.compare(workspaceId, projectId, from, to, parsed, source, campaign, campaignMissing);
        RetentionCohortService.Dimension retentionDimension = RetentionCohortService.Dimension.valueOf(dimension);
        List<RetentionCohortService.SummaryRow> retention = retentionCohortService.summary(
                workspaceId, projectId, from, to, retentionDimension, source, campaign, campaignMissing, retentionAgeDays);
        return new ComparisonResponse(
                workspaceId,
                projectId,
                from,
                to,
                dimension,
                source,
                campaign,
                campaignMissing,
                rows,
                retentionAgeDays,
                retention,
                unavailableMetrics(rows, retention));
    }

    private static List<UnavailableMetric> unavailableMetrics(
            List<SourceComparisonService.ComparisonRow> rows,
            List<RetentionCohortService.SummaryRow> retention) {
        boolean noAcquisitionCohort = rows.stream().anyMatch(row -> retention.stream()
                .noneMatch(summary -> sameRetentionKey(row, summary)));
        boolean maturityPending = retention.stream().anyMatch(summary -> !summary.cell().available());
        if (!noAcquisitionCohort && !maturityPending) {
            return List.of();
        }

        List<String> reasons = new java.util.ArrayList<>();
        if (maturityPending) {
            reasons.add(RetentionCohortService.REASON_MATURITY_PENDING);
        }
        if (noAcquisitionCohort) {
            reasons.add("NO_ACQUISITION_COHORT");
        }
        String reason = "Unavailable for one or more comparison rows: " + String.join(", ", reasons) + ".";
        return List.of(
                new UnavailableMetric("RETAINED_MRR", reason),
                new UnavailableMetric("NRR", reason));
    }

    private static boolean sameRetentionKey(
            SourceComparisonService.ComparisonRow row,
            RetentionCohortService.SummaryRow summary) {
        return java.util.Objects.equals(row.dimensionValue(), summary.dimensionValue())
                && row.attributed() == summary.attributed()
                && row.currency().equals(summary.currency());
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

    /**
     * {@code dimension} is one of {@code SOURCE, CAMPAIGN, LANDING_PAGE}. {@code source}/{@code
     * campaign}/{@code campaignMissing} echo the parent drill-down filters that were applied (null/
     * false unless the request scoped one). {@code campaignMissing} selects the "no campaign
     * captured" bucket explicitly, as a boolean rather than a sentinel value in {@code campaign}, so
     * a real UTM campaign can never collide with the missing-value bucket.
     *
     * <p>{@code retention} is #25's Retained MRR / NRR for {@code retentionAgeDays} (30/60/90,
     * default 30), one row per {@code (dimensionValue, attributed, currency)} -- the same key {@link
     * SourceComparisonService.ComparisonRow} uses, so a client joins the two lists by that tuple. A
     * dimension value with no corresponding {@code retention} row, or whose {@link
     * RetentionCohortService.AgeCell#available()} is false, has no authoritative Retained MRR/NRR yet
     * and must render "Unavailable" -- never a fabricated zero. {@code unavailableMetrics} preserves
     * #23's response contract: it is empty when every comparison row has authoritative values and
     * otherwise names Retained MRR and NRR with the applicable stable reason codes.
     */
    public record ComparisonResponse(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            String dimension,
            String source,
            String campaign,
            boolean campaignMissing,
            List<SourceComparisonService.ComparisonRow> rows,
            int retentionAgeDays,
            List<RetentionCohortService.SummaryRow> retention,
            List<UnavailableMetric> unavailableMetrics) {}

    /** Preserves #23's explicit unavailable-metric contract while #25 supplies row-level outcomes. */
    public record UnavailableMetric(String metric, String reason) {}
}
