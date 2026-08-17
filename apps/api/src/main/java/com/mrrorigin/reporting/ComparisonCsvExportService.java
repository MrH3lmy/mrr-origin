package com.mrrorigin.reporting;

import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * {@code comparison-v1} CSV export (#26): one row per {@code (dimension bucket, currency)}, pivoting
 * {@link SourceComparisonService}'s NEW/CHURN rows together and joining {@link
 * RetentionCohortService#summary} at one caller-selected age, exactly as {@code
 * RevenueOverviewController#comparison} already joins them for the JSON response -- this export
 * reuses that same pair of read models, it does not reimplement their calculations.
 *
 * <p>See {@code docs/weekly-summary-export-plan.md} §5a for the frozen column order.
 */
@Service
class ComparisonCsvExportService {

    static final String SCHEMA_VERSION = "comparison-v1";

    static final List<String> HEADER = List.of(
            "dimension", "dimension_value", "dimension_bucket", "currency", "period_start", "period_end",
            "new_mrr_amount_minor", "new_mrr_customer_count", "churned_mrr_amount_minor",
            "churned_mrr_customer_count", "retention_age_days", "retained_mrr_available",
            "retained_mrr_amount_minor", "retention_percentage_available", "retention_percentage",
            "nrr_available", "nrr", "unavailable_reason", "evidence_link");

    private final SourceComparisonService comparisonService;
    private final RetentionCohortService retentionCohortService;

    ComparisonCsvExportService(
            SourceComparisonService comparisonService, RetentionCohortService retentionCohortService) {
        this.comparisonService = comparisonService;
        this.retentionCohortService = retentionCohortService;
    }

    long write(
            Writer out,
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            SourceComparisonService.Dimension dimension,
            String source,
            String campaign,
            boolean campaignMissing,
            int retentionAgeDays)
            throws IOException {
        List<SourceComparisonService.ComparisonRow> rows =
                comparisonService.compare(workspaceId, projectId, from, to, dimension, source, campaign, campaignMissing);
        RetentionCohortService.Dimension retentionDimension =
                RetentionCohortService.Dimension.valueOf(dimension.name());
        List<RetentionCohortService.SummaryRow> retention = retentionCohortService.summary(
                workspaceId, projectId, from, to, retentionDimension, source, campaign, campaignMissing,
                retentionAgeDays);

        Map<Key, long[]> totals = new LinkedHashMap<>();
        for (SourceComparisonService.ComparisonRow row : rows) {
            Key key = new Key(row.dimensionValue(), row.attributed(), row.currency());
            long[] t = totals.computeIfAbsent(key, k -> new long[4]);
            if ("NEW".equals(row.movementType())) {
                t[0] = row.totalMinor();
                t[1] = row.customerCount();
            } else {
                t[2] = row.totalMinor();
                t[3] = row.customerCount();
            }
        }
        Map<Key, RetentionCohortService.SummaryRow> retentionByKey = new LinkedHashMap<>();
        for (RetentionCohortService.SummaryRow r : retention) {
            retentionByKey.put(new Key(r.dimensionValue(), r.attributed(), r.currency()), r);
        }

        List<Key> ordered = totals.keySet().stream()
                .sorted(Comparator.comparing(Key::currency).thenComparing(Key::valueSortKey))
                .toList();

        CsvWriter.writeRow(out, HEADER);
        long count = 0;
        for (Key key : ordered) {
            long[] t = totals.get(key);
            RetentionCohortService.SummaryRow r = retentionByKey.get(key);
            boolean available = r != null && r.cell().available();
            String unavailableReason = available
                    ? null
                    : (r == null ? "NO_ACQUISITION_COHORT" : r.cell().unavailableReason());

            List<String> fields = new ArrayList<>(HEADER.size());
            fields.add(dimension.name());
            fields.add(key.dimensionValue());
            fields.add(key.bucket());
            fields.add(key.currency());
            fields.add(from.toString());
            fields.add(to.toString());
            fields.add(String.valueOf(t[0]));
            fields.add(String.valueOf(t[1]));
            fields.add(String.valueOf(t[2]));
            fields.add(String.valueOf(t[3]));
            fields.add(String.valueOf(retentionAgeDays));
            fields.add(String.valueOf(available));
            fields.add(available ? String.valueOf(r.cell().retainedMrrMinor()) : "");
            fields.add(String.valueOf(available));
            fields.add(available && r.cell().retentionPercentage() != null ? String.valueOf(r.cell().retentionPercentage()) : "");
            fields.add(String.valueOf(available));
            fields.add(available && r.cell().nrr() != null ? String.valueOf(r.cell().nrr()) : "");
            fields.add(unavailableReason);
            fields.add(EvidenceLink.path(
                    workspaceId, projectId, from, to, null, key.currency(),
                    dimension == SourceComparisonService.Dimension.SOURCE ? key.dimensionValue() : source,
                    dimension == SourceComparisonService.Dimension.SOURCE && "UNATTRIBUTED".equals(key.bucket()),
                    dimension == SourceComparisonService.Dimension.SOURCE && "NONE".equals(key.bucket()),
                    dimension == SourceComparisonService.Dimension.CAMPAIGN
                            ? key.dimensionValue()
                            : (dimension == SourceComparisonService.Dimension.LANDING_PAGE ? campaign : null),
                    dimension == SourceComparisonService.Dimension.CAMPAIGN
                            ? "NONE".equals(key.bucket())
                            : (dimension == SourceComparisonService.Dimension.LANDING_PAGE && campaignMissing),
                    dimension == SourceComparisonService.Dimension.LANDING_PAGE ? key.dimensionValue() : null,
                    dimension == SourceComparisonService.Dimension.LANDING_PAGE && "NONE".equals(key.bucket())));
            CsvWriter.writeRow(out, fields);
            count++;
        }
        return count;
    }

    private record Key(String dimensionValue, boolean attributed, String currency) {
        /** {@code NONE} (evidence exists, field not captured), {@code UNATTRIBUTED} (SOURCE only), or {@code null}. */
        String bucket() {
            return dimensionValue != null ? null : (attributed ? "NONE" : "UNATTRIBUTED");
        }

        /** Real values sort before {@code NONE} before {@code UNATTRIBUTED}. */
        String valueSortKey() {
            if (dimensionValue != null) {
                return "0" + dimensionValue;
            }
            return attributed ? "1" : "2";
        }
    }
}
