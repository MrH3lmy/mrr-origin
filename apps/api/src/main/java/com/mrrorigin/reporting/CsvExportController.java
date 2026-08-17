package com.mrrorigin.reporting;

import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * #26's three v1 CSV exports (comparison/retention-cohorts/customers): tenant/project-scoped and
 * role-authorized exactly like every other reporting endpoint, streamed synchronously to the
 * response as rows are produced (never buffered into one in-memory CSV string), with a stable
 * {@code X-Export-Schema-Version} header and an audit entry recorded after the write completes.
 * See {@code docs/weekly-summary-export-plan.md} §5 for the frozen column contracts.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports")
public class CsvExportController {

    private static final int DEFAULT_RETENTION_AGE_DAYS = 30;

    private final ComparisonCsvExportService comparisonCsvExportService;
    private final RetentionCohortsCsvExportService retentionCohortsCsvExportService;
    private final CustomersCsvExportService customersCsvExportService;
    private final ExportAuditService exportAuditService;
    private final WorkspaceContext workspaceContext;

    public CsvExportController(
            ComparisonCsvExportService comparisonCsvExportService,
            RetentionCohortsCsvExportService retentionCohortsCsvExportService,
            CustomersCsvExportService customersCsvExportService,
            ExportAuditService exportAuditService,
            WorkspaceContext workspaceContext) {
        this.comparisonCsvExportService = comparisonCsvExportService;
        this.retentionCohortsCsvExportService = retentionCohortsCsvExportService;
        this.customersCsvExportService = customersCsvExportService;
        this.exportAuditService = exportAuditService;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping("/comparison")
    public void exportComparison(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam String dimension,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String campaign,
            @RequestParam(required = false, defaultValue = "false") boolean campaignMissing,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_RETENTION_AGE_DAYS) int retentionAgeDays,
            HttpServletResponse response)
            throws IOException {
        workspaceContext.requireMembership(workspaceId);
        SourceComparisonService.Dimension parsedDimension = parseDimension(dimension);

        prepareResponse(response, ComparisonCsvExportService.SCHEMA_VERSION, "comparison");
        long rowCount;
        try (Writer writer = response.getWriter()) {
            rowCount = comparisonCsvExportService.write(
                    writer, workspaceId, projectId, from, to, parsedDimension, source, campaign, campaignMissing,
                    retentionAgeDays);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("from", from.toString());
        filters.put("to", to.toString());
        filters.put("dimension", dimension);
        filters.put("source", source);
        filters.put("campaign", campaign);
        filters.put("campaignMissing", campaignMissing);
        filters.put("retentionAgeDays", retentionAgeDays);
        exportAuditService.record(
                workspaceId, projectId, "COMPARISON", ComparisonCsvExportService.SCHEMA_VERSION,
                workspaceContext.subjectId(), filters, rowCount);
    }

    @GetMapping("/retention-cohorts")
    public void exportRetentionCohorts(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam String dimension,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String campaign,
            @RequestParam(required = false, defaultValue = "false") boolean campaignMissing,
            HttpServletResponse response)
            throws IOException {
        workspaceContext.requireMembership(workspaceId);
        RetentionCohortService.Dimension parsedDimension = parseRetentionDimension(dimension);

        prepareResponse(response, RetentionCohortsCsvExportService.SCHEMA_VERSION, "retention-cohorts");
        long rowCount;
        try (Writer writer = response.getWriter()) {
            rowCount = retentionCohortsCsvExportService.write(
                    writer, workspaceId, projectId, parsedDimension, source, campaign, campaignMissing);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("dimension", dimension);
        filters.put("source", source);
        filters.put("campaign", campaign);
        filters.put("campaignMissing", campaignMissing);
        exportAuditService.record(
                workspaceId, projectId, "RETENTION_COHORTS", RetentionCohortsCsvExportService.SCHEMA_VERSION,
                workspaceContext.subjectId(), filters, rowCount);
    }

    @GetMapping("/customers")
    public void exportCustomers(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, HttpServletResponse response)
            throws IOException {
        workspaceContext.requireMembership(workspaceId);
        boolean canViewSensitiveIdentity = workspaceContext.canManage(workspaceId);

        prepareResponse(response, CustomersCsvExportService.SCHEMA_VERSION, "customers");
        long rowCount;
        try (Writer writer = response.getWriter()) {
            rowCount = customersCsvExportService.write(writer, workspaceId, projectId, canViewSensitiveIdentity);
        }
        exportAuditService.record(
                workspaceId, projectId, "CUSTOMERS", CustomersCsvExportService.SCHEMA_VERSION,
                workspaceContext.subjectId(), Map.of(), rowCount);
    }

    private static void prepareResponse(HttpServletResponse response, String schemaVersion, String filename) {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("X-Export-Schema-Version", schemaVersion);
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "-" + schemaVersion + ".csv\"");
    }

    private static SourceComparisonService.Dimension parseDimension(String dimension) {
        try {
            return SourceComparisonService.Dimension.valueOf(dimension);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "dimension must be one of " + Set.of("SOURCE", "CAMPAIGN", "LANDING_PAGE"));
        }
    }

    private static RetentionCohortService.Dimension parseRetentionDimension(String dimension) {
        try {
            return RetentionCohortService.Dimension.valueOf(dimension);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "dimension must be one of " + Set.of("SOURCE", "CAMPAIGN", "LANDING_PAGE"));
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }
}
