package com.mrrorigin.workspaceexport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionWorkspaceExportService;
import com.mrrorigin.billing.BillingWorkspaceExportService;
import com.mrrorigin.notification.NotificationWorkspaceExportService;
import com.mrrorigin.reporting.ReportingWorkspaceExportService;
import com.mrrorigin.revenue.RevenueWorkspaceExportService;
import com.mrrorigin.tracking.TrackingWorkspaceExportService;

import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates #64's manager-only, synchronously streamed ZIP workspace data export. Calls each
 * domain module's own {@code *WorkspaceExportService} directly (no shared interface, no polymorphic
 * dispatch -- the same shape {@code WorkspaceDeletionRequestService} uses for the six
 * {@code *WorkspaceDataDeletionService} siblings), never reaching into another module's persistence
 * internals.
 *
 * <p><b>Never buffering the full export.</b> Each of the six NDJSON files is written directly as a
 * {@link ZipOutputStream} entry, one page of rows at a time (each domain service's own bounded
 * keyset pagination) -- the export's row content is never held in memory, only streamed straight
 * through to the response. {@code manifest.json}'s accepted contract requires each file's row count,
 * which is only known once that file has been fully streamed; buffering a file's content until its
 * count was known would violate the "never buffer" requirement, so instead this only accumulates a
 * {@code long} counter per file (not its content) and writes {@code manifest.json} as the final ZIP
 * entry once every count is known. A ZIP reader locates entries by name via the archive's central
 * directory, not by physical entry order, so writing the manifest last does not conflict with it
 * being "at the ZIP root" (root means no directory prefix, not first-written).
 *
 * <p><b>Audited only on success.</b> {@link WorkspaceExportAuditService#record} is called only after
 * every NDJSON entry and the manifest have been written without error -- an export that fails partway
 * (e.g. the client disconnects mid-stream) is never recorded as a successful export, matching {@code
 * CsvExportController}'s existing audit-after-write pattern.
 */
@Service
public class WorkspaceDataExportService {

    public static final String SCHEMA_VERSION = "workspace-export-v1";

    private final BillingWorkspaceExportService billing;
    private final RevenueWorkspaceExportService revenue;
    private final AttributionWorkspaceExportService attribution;
    private final ReportingWorkspaceExportService reporting;
    private final NotificationWorkspaceExportService notification;
    private final TrackingWorkspaceExportService tracking;
    private final WorkspaceExportAuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    WorkspaceDataExportService(
            BillingWorkspaceExportService billing,
            RevenueWorkspaceExportService revenue,
            AttributionWorkspaceExportService attribution,
            ReportingWorkspaceExportService reporting,
            NotificationWorkspaceExportService notification,
            TrackingWorkspaceExportService tracking,
            WorkspaceExportAuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.billing = billing;
        this.revenue = revenue;
        this.attribution = attribution;
        this.reporting = reporting;
        this.notification = notification;
        this.tracking = tracking;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void streamExport(UUID workspaceId, String actorSubjectId, OutputStream out) throws IOException {
        OffsetDateTime exportedAt = OffsetDateTime.now(clock);
        List<FileSummary> files = new ArrayList<>(6);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            files.add(writeDomain(zip, "billing.ndjson", w -> billing.writeNdjson(workspaceId, w)));
            files.add(writeDomain(zip, "revenue.ndjson", w -> revenue.writeNdjson(workspaceId, w)));
            files.add(writeDomain(zip, "attribution.ndjson", w -> attribution.writeNdjson(workspaceId, w)));
            files.add(writeDomain(zip, "reporting.ndjson", w -> reporting.writeNdjson(workspaceId, w)));
            files.add(writeDomain(zip, "notification.ndjson", w -> notification.writeNdjson(workspaceId, w)));
            files.add(writeDomain(zip, "tracking.ndjson", w -> tracking.writeNdjson(workspaceId, w)));
            writeManifest(zip, workspaceId, actorSubjectId, exportedAt, files);
        }

        Map<String, Long> rowCounts = new LinkedHashMap<>();
        long total = 0;
        for (FileSummary file : files) {
            rowCounts.put(domainName(file.name()), file.rowCount());
            total += file.rowCount();
        }
        auditService.record(workspaceId, SCHEMA_VERSION, actorSubjectId, rowCounts, total);
    }

    private FileSummary writeDomain(ZipOutputStream zip, String name, NdjsonWriter writer) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Writer entryWriter = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        long rowCount = writer.write(entryWriter);
        entryWriter.flush();
        zip.closeEntry();
        return new FileSummary(name, rowCount);
    }

    private void writeManifest(
            ZipOutputStream zip, UUID workspaceId, String actorSubjectId, OffsetDateTime exportedAt, List<FileSummary> files)
            throws IOException {
        List<Map<String, Object>> fileEntries = new ArrayList<>(files.size());
        for (FileSummary file : files) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", file.name());
            entry.put("rowCount", file.rowCount());
            fileEntries.add(entry);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("workspaceId", workspaceId.toString());
        manifest.put("exportedAt", exportedAt.toString());
        manifest.put("actorSubjectId", actorSubjectId);
        manifest.put("files", fileEntries);
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(objectMapper.writeValueAsBytes(manifest));
        zip.closeEntry();
    }

    private static String domainName(String fileName) {
        return fileName.substring(0, fileName.length() - ".ndjson".length());
    }

    @FunctionalInterface
    private interface NdjsonWriter {
        long write(Writer out) throws IOException;
    }

    private record FileSummary(String name, long rowCount) {}
}
