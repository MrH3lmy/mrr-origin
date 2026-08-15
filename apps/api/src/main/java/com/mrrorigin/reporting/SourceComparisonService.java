package com.mrrorigin.reporting;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.revenue.RevenueCalculationService;

/**
 * Read-only, workspace/project-scoped New-MRR/Churned-MRR comparison by acquisition source,
 * campaign, or landing page (#23). Built entirely from the same already-derived {@code
 * customer_mrr_movements} (revenue, {@value RevenueCalculationService#CALCULATION_VERSION}) and
 * {@code customer_attribution_results} (attribution, {@value AttributionV1Engine#MODEL_VERSION})
 * that {@link RevenueOverviewService#sourceHighlights} already reads -- this service introduces no
 * new MRR or attribution calculation rules, only a wider grouping (campaign/landing-page dimensions,
 * plus Churned MRR) over the same evidence columns.
 *
 * <p>Reuses {@link RevenueMovementsService#OWNER_CTE} for project ownership, matching every other
 * reporting read model so a movement can never be counted toward two projects' comparisons at once.
 *
 * <p>Follows the drill-down hierarchy in DESIGN_SYSTEM.md ("Sources (#23)"): {@link Dimension#SOURCE}
 * is always available; {@link Dimension#CAMPAIGN} requires a parent {@code source}; {@link
 * Dimension#LANDING_PAGE} requires a parent {@code source} and {@code campaign}. Only {@code
 * confidence = STRONG} movements carry campaign/landing-page evidence, so those two levels only ever
 * compare within an already-attributed source -- there is no "unattributed campaign" bucket, only an
 * "unattributed source" bucket at the top level.
 *
 * <p>Retained MRR and NRR are deliberately not included: no cohort data model or approved
 * calculation ADR exists yet (#25). {@link #UNAVAILABLE_METRICS} names that gap explicitly in every
 * response so a client can never mistake "not computed" for a measured zero.
 */
@Service
public class SourceComparisonService {

    /** See {@link RevenueMovementsService#OWNER_CTE} for the identical fragment and its rationale. */
    static final String OWNER_CTE = RevenueMovementsService.OWNER_CTE;

    /** Movement types this comparison reports; expansion/contraction/reactivation are out of #23's scope. */
    private static final List<String> COMPARISON_MOVEMENT_TYPES = List.of("NEW", "CHURN");

    /** Sentinel selecting the "no campaign captured" / "no landing page captured" bucket, mirroring the
     * {@code UNATTRIBUTED} sentinel {@link RevenueMovementsService} already uses for source. */
    static final String NONE = "NONE";

    public static final List<UnavailableMetric> UNAVAILABLE_METRICS = List.of(
            new UnavailableMetric(
                    "RETAINED_MRR",
                    "Not available yet — depends on 30/60/90-day retention cohorts (#25)."),
            new UnavailableMetric(
                    "NRR",
                    "Not available yet — depends on 30/60/90-day retention cohorts (#25)."));

    public enum Dimension {
        SOURCE,
        CAMPAIGN,
        LANDING_PAGE
    }

    private final JdbcClient db;

    public SourceComparisonService(JdbcClient db) {
        this.db = db;
    }

    public List<ComparisonRow> compare(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            Dimension dimension,
            String source,
            String campaign) {
        require(workspaceId, projectId, from, to);
        return switch (dimension) {
            case SOURCE -> bySource(workspaceId, projectId, from, to);
            case CAMPAIGN -> {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("source is required to compare campaigns within it");
                }
                yield byCampaign(workspaceId, projectId, from, to, source);
            }
            case LANDING_PAGE -> {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("source is required to compare landing pages within it");
                }
                if (campaign == null || campaign.isBlank()) {
                    throw new IllegalArgumentException(
                            "campaign is required to compare landing pages within it (use '" + NONE + "' for the no-campaign bucket)");
                }
                yield byLandingPage(workspaceId, projectId, from, to, source, campaign);
            }
        };
    }

    private List<ComparisonRow> bySource(UUID w, UUID p, OffsetDateTime from, OffsetDateTime to) {
        return db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT
                          CASE WHEN r.confidence = 'STRONG' THEN r.first_source ELSE NULL END AS dim,
                          m.currency, m.movement_type, SUM(m.amount_minor) AS total_minor, COUNT(*) AS customer_count
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        LEFT JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv
                          AND m.movement_type IN (:types)
                          AND m.effective_at >= :from AND m.effective_at < :to
                        GROUP BY dim, m.currency, m.movement_type
                        ORDER BY total_minor DESC, dim ASC NULLS LAST, m.currency, m.movement_type
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("from", from)
                .param("to", to)
                .param("types", COMPARISON_MOVEMENT_TYPES)
                .query(SourceComparisonService::row)
                .list();
    }

    private List<ComparisonRow> byCampaign(UUID w, UUID p, OffsetDateTime from, OffsetDateTime to, String source) {
        return db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT
                          r.first_campaign AS dim,
                          m.currency, m.movement_type, SUM(m.amount_minor) AS total_minor, COUNT(*) AS customer_count
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv
                          AND m.movement_type IN (:types)
                          AND m.effective_at >= :from AND m.effective_at < :to
                          AND r.confidence = 'STRONG' AND r.first_source = :source
                        GROUP BY dim, m.currency, m.movement_type
                        ORDER BY total_minor DESC, dim ASC NULLS LAST, m.currency, m.movement_type
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("from", from)
                .param("to", to)
                .param("types", COMPARISON_MOVEMENT_TYPES)
                .param("source", source)
                .query(SourceComparisonService::row)
                .list();
    }

    private List<ComparisonRow> byLandingPage(
            UUID w, UUID p, OffsetDateTime from, OffsetDateTime to, String source, String campaign) {
        boolean noCampaign = NONE.equals(campaign);
        return db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT
                          r.first_landing_page AS dim,
                          m.currency, m.movement_type, SUM(m.amount_minor) AS total_minor, COUNT(*) AS customer_count
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv
                          AND m.movement_type IN (:types)
                          AND m.effective_at >= :from AND m.effective_at < :to
                          AND r.confidence = 'STRONG' AND r.first_source = :source
                          AND ((:noCampaign = TRUE AND r.first_campaign IS NULL)
                               OR (:noCampaign = FALSE AND r.first_campaign = :campaign))
                        GROUP BY dim, m.currency, m.movement_type
                        ORDER BY total_minor DESC, dim ASC NULLS LAST, m.currency, m.movement_type
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("from", from)
                .param("to", to)
                .param("types", COMPARISON_MOVEMENT_TYPES)
                .param("source", source)
                .param("noCampaign", noCampaign)
                .param("campaign", noCampaign ? "" : campaign)
                .query(SourceComparisonService::row)
                .list();
    }

    private static ComparisonRow row(ResultSet rs, int n) throws SQLException {
        return new ComparisonRow(
                rs.getString("dim"),
                rs.getString("currency"),
                rs.getString("movement_type"),
                rs.getLong("total_minor"),
                rs.getLong("customer_count"));
    }

    private static void require(UUID w, UUID p, OffsetDateTime from, OffsetDateTime to) {
        if (w == null || p == null || from == null || to == null) {
            throw new IllegalArgumentException("workspace, project, from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    /**
     * One aggregated cell of the comparison table. {@code dimensionValue} is null when the row is the
     * "no evidence at this level" bucket: at {@link Dimension#SOURCE} that means Unattributed; at
     * {@link Dimension#CAMPAIGN}/{@link Dimension#LANDING_PAGE} it means the parent touch was strongly
     * attributed but this specific UTM field was not captured. {@code movementType} is {@code NEW} or
     * {@code CHURN}.
     */
    public record ComparisonRow(
            String dimensionValue, String currency, String movementType, long totalMinor, long customerCount) {}

    /** Names a product metric this comparison does not compute yet, and why, so a client can never mistake absence for a measured zero. */
    public record UnavailableMetric(String metric, String reason) {}
}
