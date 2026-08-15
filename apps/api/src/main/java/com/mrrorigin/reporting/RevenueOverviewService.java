package com.mrrorigin.reporting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.revenue.RevenueCalculationService;

/**
 * Read-only, workspace/project-scoped MRR movement, current-MRR, and source-highlight summaries
 * for the founder overview (#22). Built entirely from already-derived {@code customer_mrr_movements}
 * / {@code customer_mrr_snapshots} (revenue, {@value RevenueCalculationService#CALCULATION_VERSION})
 * and {@code customer_attribution_results} (attribution, {@value AttributionV1Engine#MODEL_VERSION})
 * -- this service introduces no new MRR or attribution calculation rules.
 *
 * <p>Project scope resolves one "owning project" per Stripe customer via {@link #OWNER_CTE}: the
 * customer's currently active {@code stripe_customer_links} project when one exists, otherwise the
 * project of that customer's most recently calculated {@code customer_attribution_results} row.
 * {@code customer_mrr_movements}/{@code customer_mrr_snapshots}/{@code billing_customers} carry no
 * {@code project_id} of their own -- a Stripe customer's project is only knowable once something
 * links it -- and {@link com.mrrorigin.attribution.AttributionApplicationService#recalculate}
 * re-stamps a customer's <em>entire</em> movement history under whichever project last recalculated
 * it, so a customer who moves from project A to project B can end up with {@code
 * customer_attribution_results} rows for the same movement under both projects. Resolving a single
 * owner per customer (most-recent {@code calculated_at} wins, active link wins over any calculated
 * row) keeps a movement from counting toward two projects' revenue at once. See {@link
 * RevenueMovementsService} for the identical resolution used by the drill-down.
 *
 * <p>Amounts are grouped by currency and never summed across currencies: reporting-currency
 * conversion is an explicitly deferred architecture decision (see ARCHITECTURE.md).
 *
 * <p>"Retained MRR" (30/60/90-day acquisition-cohort survival) is deliberately not included here.
 * No cohort data model or approved calculation ADR exists yet; that is the separate Retention
 * outcome (#25).
 */
@Service
public class RevenueOverviewService {

    /** See {@link RevenueMovementsService#OWNER_CTE} for the identical fragment and its rationale. */
    static final String OWNER_CTE = RevenueMovementsService.OWNER_CTE;

    private final JdbcClient db;

    public RevenueOverviewService(JdbcClient db) {
        this.db = db;
    }

    public Overview overview(UUID workspaceId, UUID projectId, OffsetDateTime from, OffsetDateTime to) {
        require(workspaceId, projectId, from, to);
        return new Overview(
                workspaceId,
                projectId,
                from,
                to,
                RevenueCalculationService.CALCULATION_VERSION,
                AttributionV1Engine.MODEL_VERSION,
                movementTotals(workspaceId, projectId, from, to),
                currentMrr(workspaceId, projectId, to),
                sourceHighlights(workspaceId, projectId, from, to));
    }

    private List<MovementTotal> movementTotals(UUID w, UUID p, OffsetDateTime from, OffsetDateTime to) {
        return db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT m.currency, m.movement_type, SUM(m.amount_minor) AS total_minor, COUNT(*) AS movement_count
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv
                          AND m.effective_at >= :from AND m.effective_at < :to
                        GROUP BY m.currency, m.movement_type
                        ORDER BY m.currency, m.movement_type
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new MovementTotal(
                        rs.getString("currency"), rs.getString("movement_type"),
                        rs.getLong("total_minor"), rs.getLong("movement_count")))
                .list();
    }

    private List<CurrentMrr> currentMrr(UUID w, UUID p, OffsetDateTime asOf) {
        return db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        , latest AS (
                          SELECT DISTINCT ON (s.stripe_customer_id, s.currency)
                                 s.stripe_customer_id, s.currency, s.amount_minor
                          FROM customer_mrr_snapshots s
                          JOIN owner o ON o.customer_id = s.stripe_customer_id AND o.owning_project_id = :p
                          WHERE s.workspace_id = :w AND s.calculation_version = :cv
                            AND s.supported = TRUE AND s.effective_at <= :asOf
                          ORDER BY s.stripe_customer_id, s.currency, s.effective_at DESC
                        )
                        SELECT currency, SUM(amount_minor) AS total_minor, COUNT(*) AS customer_count
                        FROM latest
                        WHERE amount_minor > 0
                        GROUP BY currency
                        ORDER BY currency
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("asOf", asOf)
                .query((rs, n) -> new CurrentMrr(
                        rs.getString("currency"), rs.getLong("total_minor"), rs.getLong("customer_count")))
                .list();
    }

    private List<SourceHighlight> sourceHighlights(UUID w, UUID p, OffsetDateTime from, OffsetDateTime to) {
        return db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT
                          CASE WHEN r.confidence = 'STRONG' THEN r.first_source ELSE NULL END AS source,
                          m.currency, SUM(m.amount_minor) AS total_minor, COUNT(*) AS customer_count
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        LEFT JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv AND m.movement_type = 'NEW'
                          AND m.effective_at >= :from AND m.effective_at < :to
                        GROUP BY source, m.currency
                        ORDER BY total_minor DESC
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new SourceHighlight(
                        rs.getString("source"), rs.getString("currency"),
                        rs.getLong("total_minor"), rs.getLong("customer_count")))
                .list();
    }

    private static void require(UUID w, UUID p, OffsetDateTime from, OffsetDateTime to) {
        if (w == null || p == null || from == null || to == null) {
            throw new IllegalArgumentException("workspace, project, from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    public record Overview(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            String calculationVersion,
            String modelVersion,
            List<MovementTotal> movementTotals,
            List<CurrentMrr> currentMrr,
            List<SourceHighlight> sourceHighlights) {}

    /** {@code movementType} is one of {@code NEW, EXPANSION, CONTRACTION, CHURN, REACTIVATION}. */
    public record MovementTotal(String currency, String movementType, long totalMinor, long movementCount) {}

    public record CurrentMrr(String currency, long totalMinor, long customerCount) {}

    /** {@code source} is null when the New MRR in this bucket is not strongly attributed. */
    public record SourceHighlight(String source, String currency, long totalMinor, long customerCount) {}
}
