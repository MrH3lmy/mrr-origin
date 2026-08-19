package com.mrrorigin.workspaceexport;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * #64's manager-only workspace data export: a synchronously streamed ZIP containing a versioned
 * {@code manifest.json} plus one NDJSON file per workspace-owned domain. Authorization is exactly
 * {@link WorkspaceContext#requireManager}, "the same bar as other management mutations" per the
 * accepted contract -- including its side effect of a {@code 409} once the workspace is {@code
 * DELETING} (ADR-0008), inherited automatically like every other manager-gated endpoint.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/exports")
public class WorkspaceDataExportController {

    private final WorkspaceContext workspaceContext;
    private final WorkspaceDataExportService exportService;

    public WorkspaceDataExportController(WorkspaceContext workspaceContext, WorkspaceDataExportService exportService) {
        this.workspaceContext = workspaceContext;
        this.exportService = exportService;
    }

    @GetMapping("/data")
    public void exportData(@PathVariable UUID workspaceId, HttpServletResponse response) throws IOException {
        workspaceContext.requireManager(workspaceId);

        response.setContentType("application/zip");
        response.setHeader("X-Export-Schema-Version", WorkspaceDataExportService.SCHEMA_VERSION);
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"workspace-export-" + workspaceId + ".zip\"");

        exportService.streamExport(workspaceId, workspaceContext.subjectId(), response.getOutputStream());
    }
}
