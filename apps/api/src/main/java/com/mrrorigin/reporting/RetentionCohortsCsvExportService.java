package com.mrrorigin.reporting;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mrrorigin.reporting.RetentionCohortService.AgeCell;
import com.mrrorigin.reporting.RetentionCohortService.CohortRow;

/**
 * {@code retention-cohorts-v1} CSV export (#26): one row per {@code (dimension bucket, currency,
 * acquisition period)}, reusing {@link RetentionCohortService#heatmap} verbatim -- no new
 * calculation rule, no re-derivation of {@code AgeCell}'s unavailable-vs-available contract.
 *
 * <p>See {@code docs/weekly-summary-export-plan.md} §5b for the frozen column order.
 */
@Service
class RetentionCohortsCsvExportService {

    static final String SCHEMA_VERSION = "retention-cohorts-v1";

    static final List<String> HEADER = List.of(
            "dimension", "dimension_value", "dimension_bucket", "currency", "period_start", "period_end",
            "starting_mrr_amount_minor", "sample_size",
            "age30_available", "age30_retained_mrr_amount_minor", "age30_retention_percentage",
            "age30_expansion_mrr_amount_minor", "age30_contraction_mrr_amount_minor",
            "age30_churn_mrr_amount_minor", "age30_reactivation_mrr_amount_minor", "age30_nrr",
            "age30_unavailable_reason",
            "age60_available", "age60_retained_mrr_amount_minor", "age60_retention_percentage",
            "age60_expansion_mrr_amount_minor", "age60_contraction_mrr_amount_minor",
            "age60_churn_mrr_amount_minor", "age60_reactivation_mrr_amount_minor", "age60_nrr",
            "age60_unavailable_reason",
            "age90_available", "age90_retained_mrr_amount_minor", "age90_retention_percentage",
            "age90_expansion_mrr_amount_minor", "age90_contraction_mrr_amount_minor",
            "age90_churn_mrr_amount_minor", "age90_reactivation_mrr_amount_minor", "age90_nrr",
            "age90_unavailable_reason",
            "evidence_link");

    private final RetentionCohortService retentionCohortService;

    RetentionCohortsCsvExportService(RetentionCohortService retentionCohortService) {
        this.retentionCohortService = retentionCohortService;
    }

    long write(
            Writer out,
            UUID workspaceId,
            UUID projectId,
            RetentionCohortService.Dimension dimension,
            String source,
            String campaign,
            boolean campaignMissing)
            throws IOException {
        List<CohortRow> rows =
                retentionCohortService.heatmap(workspaceId, projectId, dimension, source, campaign, campaignMissing);

        CsvWriter.writeRow(out, HEADER);
        long count = 0;
        for (CohortRow row : rows) {
            String bucket = row.dimensionValue() != null ? null : (row.attributed() ? "NONE" : "UNATTRIBUTED");
            List<String> fields = new ArrayList<>(HEADER.size());
            fields.add(dimension.name());
            fields.add(row.dimensionValue());
            fields.add(bucket);
            fields.add(row.currency());
            fields.add(row.periodStart().toString());
            fields.add(row.periodEnd().toString());
            fields.add(String.valueOf(row.startingMrrMinor()));
            fields.add(String.valueOf(row.sampleSize()));
            addAgeCell(fields, row.age30());
            addAgeCell(fields, row.age60());
            addAgeCell(fields, row.age90());
            fields.add(EvidenceLink.path(
                    workspaceId, projectId, row.periodStart(), row.periodEnd(), null, row.currency(),
                    dimension == RetentionCohortService.Dimension.SOURCE ? row.dimensionValue() : source,
                    dimension == RetentionCohortService.Dimension.SOURCE && "UNATTRIBUTED".equals(bucket),
                    dimension == RetentionCohortService.Dimension.SOURCE && "NONE".equals(bucket),
                    dimension == RetentionCohortService.Dimension.CAMPAIGN
                            ? row.dimensionValue()
                            : (dimension == RetentionCohortService.Dimension.LANDING_PAGE ? campaign : null),
                    dimension == RetentionCohortService.Dimension.CAMPAIGN
                            ? "NONE".equals(bucket)
                            : (dimension == RetentionCohortService.Dimension.LANDING_PAGE && campaignMissing),
                    dimension == RetentionCohortService.Dimension.LANDING_PAGE ? row.dimensionValue() : null,
                    dimension == RetentionCohortService.Dimension.LANDING_PAGE && "NONE".equals(bucket)));
            CsvWriter.writeRow(out, fields);
            count++;
        }
        return count;
    }

    private static void addAgeCell(List<String> fields, AgeCell cell) {
        fields.add(String.valueOf(cell.available()));
        fields.add(cell.available() ? String.valueOf(cell.retainedMrrMinor()) : "");
        fields.add(cell.available() && cell.retentionPercentage() != null ? String.valueOf(cell.retentionPercentage()) : "");
        fields.add(cell.available() ? String.valueOf(cell.expansionMrrMinor()) : "");
        fields.add(cell.available() ? String.valueOf(cell.contractionMrrMinor()) : "");
        fields.add(cell.available() ? String.valueOf(cell.churnMrrMinor()) : "");
        fields.add(cell.available() ? String.valueOf(cell.reactivationMrrMinor()) : "");
        fields.add(cell.available() && cell.nrr() != null ? String.valueOf(cell.nrr()) : "");
        fields.add(cell.available() ? null : cell.unavailableReason());
    }
}
