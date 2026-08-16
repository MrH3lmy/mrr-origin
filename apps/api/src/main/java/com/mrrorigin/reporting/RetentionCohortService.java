package com.mrrorigin.reporting;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.revenue.RevenueCalculationService;

/**
 * Read-only, workspace/project-scoped 30/60/90-day retained-MRR cohort read model (#25), grouped by
 * acquisition source/campaign/landing page, UTC calendar-month acquisition period, and currency. See
 * {@code docs/adr/0006-retained-mrr-cohort-read-model.md} for the grouping, maturity, and windowing
 * decisions this class implements, and ADR-0004 for the underlying Retained MRR / NRR formulas.
 *
 * <p>Like {@link SourceComparisonService}, this is a live query over already-derived {@code
 * customer_mrr_movements} ({@value RevenueCalculationService#CALCULATION_VERSION}) and {@code
 * customer_attribution_results} ({@value AttributionV1Engine#MODEL_VERSION}) -- no materialized
 * table, no new calculation rules. A recalculation or a late-arriving touchpoint is reflected on the
 * next read with no invalidation step to get wrong. Project ownership reuses {@link
 * RevenueMovementsService#OWNER_CTE} so a customer can never contribute to two projects' cohorts.
 *
 * <p>Per-member movement history is fetched once per request and aggregated in Java rather than SQL:
 * each cohort member is evaluated at their own {@code acquiredAt + age} cutoff, which a single
 * date-filtered SQL aggregate cannot express per row. This is a full per-project movement scan, the
 * same tradeoff {@link SourceComparisonService} and {@link RevenueOverviewService} already make at
 * V1's expected beta scale.
 */
@Service
public class RetentionCohortService {

    /** The only supported cohort ages, per the issue's "30/60/90-day" scope. */
    public static final List<Integer> AGES_DAYS = List.of(30, 60, 90);

    /** The age has not matured yet: not every possible member of the period could have reached it. */
    public static final String REASON_MATURITY_PENDING = "MATURITY_PENDING";

    /** See {@link RevenueMovementsService#OWNER_CTE} for the identical fragment and its rationale. */
    static final String OWNER_CTE = RevenueMovementsService.OWNER_CTE;

    private static final Set<String> POSITIVE_TYPES = Set.of("NEW", "EXPANSION", "REACTIVATION");

    private final JdbcClient db;
    private final Clock clock;

    public RetentionCohortService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Same drill-down hierarchy as {@link SourceComparisonService.Dimension}. */
    public enum Dimension {
        SOURCE,
        CAMPAIGN,
        LANDING_PAGE
    }

    /** One cohort cell per acquisition month for the requested dimension/value, with 30/60/90-day ages. */
    public List<CohortRow> heatmap(
            UUID workspaceId, UUID projectId, Dimension dimension, String source, String campaign, boolean campaignMissing) {
        require(workspaceId, projectId);
        validateDimension(dimension, source, campaign, campaignMissing);
        List<Member> members = members(workspaceId, projectId, dimension, source, campaign, campaignMissing);
        if (members.isEmpty()) return List.of();
        Map<CohortKey, List<Member>> groups = members.stream()
                .collect(Collectors.groupingBy(m ->
                        new CohortKey(m.dimensionValue(), m.attributed(), m.currency(), periodStart(m.acquiredAt()))));
        Map<String, List<Movement>> movements = movementsByMember(workspaceId, projectId);
        OffsetDateTime now = OffsetDateTime.now(clock);

        List<CohortRow> rows = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            CohortKey key = entry.getKey();
            List<Member> groupMembers = entry.getValue();
            long startingMrr = groupMembers.stream().mapToLong(Member::startingAmountMinor).sum();
            long sampleSize = groupMembers.size();
            OffsetDateTime periodEnd = key.periodStart().plusMonths(1);
            Map<Integer, AgeCell> cells = new HashMap<>();
            for (int age : AGES_DAYS) {
                OffsetDateTime boundary = periodEnd.plusDays(age);
                cells.put(age, now.isBefore(boundary)
                        ? AgeCell.unavailable(REASON_MATURITY_PENDING)
                        : aggregate(groupMembers, movements, age, startingMrr));
            }
            rows.add(new CohortRow(
                    key.dimensionValue(), key.attributed(), key.currency(), key.periodStart(), periodEnd,
                    startingMrr, sampleSize, cells.get(30), cells.get(60), cells.get(90)));
        }
        rows.sort(CohortRow.ORDER);
        return rows;
    }

    /**
     * One combined cohort row per dimension value, aggregating every acquisition period that falls in
     * {@code [from, to)}, for one caller-selected age. Powers the #23 Sources comparison integration:
     * that screen aggregates New/Churned MRR over an arbitrary period, not one acquisition month, so
     * its Retained MRR / NRR columns must do the same. Available only when every contributing period
     * is itself mature at the requested age (see ADR-0006); otherwise the whole row is unavailable.
     */
    public List<SummaryRow> summary(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            Dimension dimension,
            String source,
            String campaign,
            boolean campaignMissing,
            int ageDays) {
        require(workspaceId, projectId);
        requireRange(from, to);
        validateDimension(dimension, source, campaign, campaignMissing);
        if (!AGES_DAYS.contains(ageDays)) {
            throw new IllegalArgumentException("ageDays must be one of " + AGES_DAYS);
        }
        List<Member> members = members(workspaceId, projectId, dimension, source, campaign, campaignMissing).stream()
                .filter(m -> !m.acquiredAt().isBefore(from) && m.acquiredAt().isBefore(to))
                .toList();
        if (members.isEmpty()) return List.of();
        Map<SummaryKey, List<Member>> groups = members.stream()
                .collect(Collectors.groupingBy(m -> new SummaryKey(m.dimensionValue(), m.attributed(), m.currency())));
        Map<String, List<Movement>> movements = movementsByMember(workspaceId, projectId);
        OffsetDateTime now = OffsetDateTime.now(clock);

        List<SummaryRow> rows = new ArrayList<>();
        for (List<Member> groupMembers : groups.values()) {
            Member any = groupMembers.get(0);
            boolean allMature = groupMembers.stream()
                    .map(m -> periodStart(m.acquiredAt()).plusMonths(1).plusDays(ageDays))
                    .allMatch(boundary -> !now.isBefore(boundary));
            long startingMrr = groupMembers.stream().mapToLong(Member::startingAmountMinor).sum();
            long sampleSize = groupMembers.size();
            AgeCell cell = allMature
                    ? aggregate(groupMembers, movements, ageDays, startingMrr)
                    : AgeCell.unavailable(REASON_MATURITY_PENDING);
            rows.add(new SummaryRow(any.dimensionValue(), any.attributed(), any.currency(), startingMrr, sampleSize, cell));
        }
        rows.sort(SummaryRow.ORDER);
        return rows;
    }

    /**
     * Sums each member's movement history at the {@code age}-day cutoff. {@code retainedMrr} is the
     * signed balance at {@code effective_at <= cutoff} (inclusive); {@code expansion}/{@code
     * contraction}/{@code churn}/{@code reactivation} are the same member's movements strictly inside
     * {@code (acquiredAt, cutoff)} split by type. Per ADR-0004/ADR-0006, a movement landing exactly on
     * {@code cutoff} is included in {@code retainedMrr} but excluded from the window sums, so the two
     * can differ by exactly that movement -- documented, not a bug.
     */
    private static AgeCell aggregate(
            List<Member> members, Map<String, List<Movement>> movementsByMember, int ageDays, long startingMrr) {
        long retained = 0, expansion = 0, contraction = 0, churn = 0, reactivation = 0;
        for (Member m : members) {
            OffsetDateTime cutoff = m.acquiredAt().plusDays(ageDays);
            for (Movement mv : movementsByMember.getOrDefault(m.key(), List.of())) {
                if (!mv.effectiveAt().isAfter(cutoff)) {
                    retained += signed(mv);
                }
                if (mv.effectiveAt().isAfter(m.acquiredAt()) && mv.effectiveAt().isBefore(cutoff)) {
                    switch (mv.type()) {
                        case "EXPANSION" -> expansion += mv.amountMinor();
                        case "CONTRACTION" -> contraction += mv.amountMinor();
                        case "CHURN" -> churn += mv.amountMinor();
                        case "REACTIVATION" -> reactivation += mv.amountMinor();
                        default -> {}
                    }
                }
            }
        }
        Double retentionPercentage = ratio(retained, startingMrr);
        Double nrr = ratio(startingMrr + expansion - contraction - churn, startingMrr);
        return AgeCell.available(retained, retentionPercentage, expansion, contraction, churn, reactivation, nrr);
    }

    /** Undefined (never a fabricated zero) when {@code startingMrr} is zero -- an empty cohort. Package-visible for direct unit testing. */
    static Double ratio(long numerator, long startingMrr) {
        return startingMrr == 0 ? null : (double) numerator / startingMrr;
    }

    private static long signed(Movement mv) {
        return POSITIVE_TYPES.contains(mv.type()) ? mv.amountMinor() : -mv.amountMinor();
    }

    private List<Member> members(
            UUID w, UUID p, Dimension dimension, String source, String campaign, boolean campaignMissing) {
        List<RawAcquisition> raw = db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT m.id AS movement_id, m.stripe_customer_id, m.currency, m.amount_minor, m.effective_at,
                          r.confidence, r.first_source, r.first_campaign, r.first_landing_page
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        LEFT JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv AND m.movement_type = 'NEW'
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .query((rs, n) -> new RawAcquisition(
                        rs.getString("stripe_customer_id"),
                        rs.getString("currency"),
                        rs.getLong("amount_minor"),
                        rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getString("confidence"),
                        rs.getString("first_source"),
                        rs.getString("first_campaign"),
                        rs.getString("first_landing_page")))
                .list();

        List<Member> result = new ArrayList<>();
        for (RawAcquisition a : raw) {
            boolean strong = "STRONG".equals(a.confidence());
            switch (dimension) {
                case SOURCE -> result.add(new Member(
                        a.customerId(), a.currency(), a.effectiveAt(), a.amountMinor(),
                        strong ? a.firstSource() : null, strong));
                case CAMPAIGN -> {
                    if (strong && Objects.equals(a.firstSource(), source)) {
                        result.add(new Member(
                                a.customerId(), a.currency(), a.effectiveAt(), a.amountMinor(), a.firstCampaign(), true));
                    }
                }
                case LANDING_PAGE -> {
                    boolean campaignMatches =
                            campaignMissing ? a.firstCampaign() == null : Objects.equals(a.firstCampaign(), campaign);
                    if (strong && Objects.equals(a.firstSource(), source) && campaignMatches) {
                        result.add(new Member(
                                a.customerId(), a.currency(), a.effectiveAt(), a.amountMinor(), a.firstLandingPage(), true));
                    }
                }
            }
        }
        return result;
    }

    /** Every owner-scoped movement for this project, keyed by {@code customerId|currency}. */
    private Map<String, List<Movement>> movementsByMember(UUID w, UUID p) {
        List<Movement> all = db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT m.stripe_customer_id, m.currency, m.movement_type, m.amount_minor, m.effective_at
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv
                        """)
                .param("w", w)
                .param("p", p)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .query((rs, n) -> new Movement(
                        rs.getString("stripe_customer_id"),
                        rs.getString("currency"),
                        rs.getString("movement_type"),
                        rs.getLong("amount_minor"),
                        rs.getObject("effective_at", OffsetDateTime.class)))
                .list();
        Map<String, List<Movement>> byMember = new HashMap<>();
        for (Movement mv : all) {
            byMember.computeIfAbsent(mv.customerId() + " " + mv.currency(), k -> new ArrayList<>()).add(mv);
        }
        return byMember;
    }

    /** The UTC calendar-month start containing {@code at} -- the acquisition-period grouping key (ADR-0006). */
    private static OffsetDateTime periodStart(OffsetDateTime at) {
        OffsetDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC);
        return OffsetDateTime.of(utc.getYear(), utc.getMonthValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    private static void validateDimension(Dimension dimension, String source, String campaign, boolean campaignMissing) {
        if (campaign != null && campaignMissing) {
            throw new IllegalArgumentException("campaign and campaignMissing are mutually exclusive");
        }
        switch (dimension) {
            case SOURCE -> {}
            case CAMPAIGN -> {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("source is required to compare campaigns within it");
                }
            }
            case LANDING_PAGE -> {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("source is required to compare landing pages within it");
                }
                if (!campaignMissing && (campaign == null || campaign.isBlank())) {
                    throw new IllegalArgumentException(
                            "campaign or campaignMissing is required to compare landing pages within a campaign");
                }
            }
        }
    }

    private static void require(UUID w, UUID p) {
        if (w == null || p == null) {
            throw new IllegalArgumentException("workspace and project are required");
        }
    }

    private static void requireRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    private record RawAcquisition(
            String customerId,
            String currency,
            long amountMinor,
            OffsetDateTime effectiveAt,
            String confidence,
            String firstSource,
            String firstCampaign,
            String firstLandingPage) {}

    private record Member(
            String customerId,
            String currency,
            OffsetDateTime acquiredAt,
            long startingAmountMinor,
            String dimensionValue,
            boolean attributed) {
        String key() {
            return customerId + " " + currency;
        }
    }

    private record Movement(String customerId, String currency, String type, long amountMinor, OffsetDateTime effectiveAt) {}

    private record CohortKey(String dimensionValue, boolean attributed, String currency, OffsetDateTime periodStart) {}

    /** Collision-free summary grouping key: null bucket modes never share a string namespace with real values. */
    private record SummaryKey(String dimensionValue, boolean attributed, String currency) {}

    /**
     * One acquisition-month cohort cell. {@code dimensionValue}/{@code attributed} follow the same
     * "no value at this level" convention as {@link SourceComparisonService.ComparisonRow}: {@code
     * attributed = false} (SOURCE only) is genuinely no evidence (Unattributed); {@code attributed =
     * true} with a null {@code dimensionValue} is strongly attributed but this field wasn't captured.
     * {@code startingMrrMinor}/{@code sampleSize} are acquisition facts and are always populated;
     * {@code age30}/{@code age60}/{@code age90} are populated only once mature (ADR-0006).
     */
    public record CohortRow(
            String dimensionValue,
            boolean attributed,
            String currency,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            long startingMrrMinor,
            long sampleSize,
            AgeCell age30,
            AgeCell age60,
            AgeCell age90) {
        static final Comparator<CohortRow> ORDER = Comparator
                .comparing(CohortRow::periodStart)
                .thenComparing(CohortRow::dimensionValue, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CohortRow::attributed)
                .thenComparing(CohortRow::currency);
    }

    /** One dimension value's cohorts combined across an arbitrary {@code [from, to)} range, for one age. */
    public record SummaryRow(
            String dimensionValue,
            boolean attributed,
            String currency,
            long startingMrrMinor,
            long sampleSize,
            AgeCell cell) {
        static final Comparator<SummaryRow> ORDER = Comparator
                .comparing(SummaryRow::dimensionValue, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(SummaryRow::attributed)
                .thenComparing(SummaryRow::currency);
    }

    /**
     * {@code available = false} means immature ({@link #REASON_MATURITY_PENDING}) -- every numeric
     * field is null, never a fabricated zero. {@code retentionPercentage}/{@code nrr} are ratios
     * (0.85, not 85), and are themselves null only for an empty cohort ({@code startingMrr = 0}),
     * which the read model never actually emits a row for (see ADR-0006) but the pure calculation
     * still handles explicitly rather than dividing by zero.
     */
    public record AgeCell(
            boolean available,
            String unavailableReason,
            Long retainedMrrMinor,
            Double retentionPercentage,
            Long expansionMrrMinor,
            Long contractionMrrMinor,
            Long churnMrrMinor,
            Long reactivationMrrMinor,
            Double nrr) {
        static AgeCell unavailable(String reason) {
            return new AgeCell(false, reason, null, null, null, null, null, null, null);
        }

        static AgeCell available(
                long retained, Double retentionPercentage, long expansion, long contraction, long churn,
                long reactivation, Double nrr) {
            return new AgeCell(
                    true, null, retained, retentionPercentage, expansion, contraction, churn, reactivation, nrr);
        }
    }
}
