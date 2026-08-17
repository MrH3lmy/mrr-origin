package com.mrrorigin.notification;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.mrrorigin.reporting.EvidenceLink;
import com.mrrorigin.reporting.SourceComparisonService;
import com.mrrorigin.workspace.WorkspaceManagementService;

/**
 * #26's weekly action summary: for the last completed project-timezone week (or an explicit,
 * caller-selected one), compares New/Churned MRR per {@code (dimension, bucket, currency)} against
 * the immediately preceding completed week, walking the full SOURCE -&gt; CAMPAIGN -&gt;
 * LANDING_PAGE drill-down hierarchy exactly as {@code apps/web}'s Sources screen (#23) does, so the
 * summary and that screen always describe the same underlying comparison. Introduces no new
 * MRR/attribution calculation rule -- every number comes from {@link SourceComparisonService},
 * reused verbatim for both weeks.
 *
 * <p>Retained MRR / NRR are intentionally out of scope here: a one-week-old cohort can never be 30
 * days mature (see {@code RetentionCohortService}), so week-over-week delta language never applies
 * to them.
 *
 * <p>See {@code docs/weekly-summary-export-plan.md} for the frozen contract this class implements
 * (material-change/low-volume thresholds, evidence-filter shape, ordering).
 */
@Service
public class WeeklySummaryService {

    /** #26 decision 1: fixed low-volume threshold, per dimension bucket and currency. */
    static final long LOW_VOLUME_THRESHOLD = 5;

    /** #26 decision 2: material-change threshold, as an absolute percentage-change ratio. */
    static final double MATERIAL_CHANGE_THRESHOLD = 0.25;

    private final SourceComparisonService comparisonService;
    private final WorkspaceManagementService workspaceManagementService;
    private final Clock clock;

    public WeeklySummaryService(
            SourceComparisonService comparisonService,
            WorkspaceManagementService workspaceManagementService,
            Clock clock) {
        this.comparisonService = comparisonService;
        this.workspaceManagementService = workspaceManagementService;
        this.clock = clock;
    }

    /**
     * Authenticated-caller entry point (the {@code /reporting/weekly-summary} endpoint): resolves the
     * project's timezone via {@link WorkspaceManagementService#projectTimezone}, which -- via {@link
     * WorkspaceManagementService#getProject} -- both requires an authenticated {@code
     * WorkspaceContext} and verifies {@code projectId} actually belongs to {@code workspaceId}. Never
     * call this from a background/scheduled context; there is no authenticated caller to check
     * against there. See {@link #summary(UUID, UUID, LocalDate, String)} for that case.
     */
    public WeeklySummaryResponse summary(UUID workspaceId, UUID projectId, LocalDate weekStartOverride) {
        if (workspaceId == null || projectId == null) {
            throw new IllegalArgumentException("workspace and project are required");
        }
        String timezone = workspaceManagementService.projectTimezone(workspaceId, projectId);
        return summary(workspaceId, projectId, weekStartOverride, timezone);
    }

    /**
     * Scheduler entry point (#59's {@code WeeklySummaryDispatchService}): takes the project's
     * timezone directly instead of resolving it via {@link WorkspaceManagementService#projectTimezone},
     * which requires an authenticated {@code WorkspaceContext} that does not exist on a background
     * thread. Safe here because the caller already obtained {@code (workspaceId, projectId, timezone)}
     * from a trusted, system-level source ({@link WorkspaceManagementService#listAllProjectsForScheduling}),
     * not from external input, so no additional authorization check is needed.
     */
    public WeeklySummaryResponse summary(UUID workspaceId, UUID projectId, LocalDate weekStartOverride, String timezone) {
        if (workspaceId == null || projectId == null) {
            throw new IllegalArgumentException("workspace and project are required");
        }
        ZoneId zone = ZoneId.of(timezone);

        ZonedDateTime nowZoned = clock.instant().atZone(zone);
        ZonedDateTime weekStartZoned;
        if (weekStartOverride != null) {
            if (weekStartOverride.getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new IllegalArgumentException("weekStart must be a Monday");
            }
            weekStartZoned = weekStartOverride.atStartOfDay(zone);
        } else {
            // The most recently started project-timezone week is never itself complete yet (its end
            // boundary, one week later, cannot have passed if its start is the most recent Monday not
            // after now) -- so "last completed week" is always exactly one week before it.
            LocalDate mondayThisWeek =
                    nowZoned.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weekStartZoned = mondayThisWeek.atStartOfDay(zone).minusWeeks(1);
        }
        ZonedDateTime weekEndZoned = weekStartZoned.plusWeeks(1);
        if (weekEndZoned.isAfter(nowZoned)) {
            throw new IllegalArgumentException("weekStart must identify a completed week");
        }
        OffsetDateTime weekStart = weekStartZoned.toInstant().atOffset(ZoneOffset.UTC);
        OffsetDateTime weekEnd = weekEndZoned.toInstant().atOffset(ZoneOffset.UTC);
        OffsetDateTime priorWeekStart = weekStartZoned.minusWeeks(1).toInstant().atOffset(ZoneOffset.UTC);
        OffsetDateTime priorWeekEnd = weekStart;

        List<Insight> all = new ArrayList<>();
        List<Insight> sourceInsights = compareBothPeriods(
                workspaceId, projectId, weekStart, weekEnd, priorWeekStart, priorWeekEnd,
                SourceComparisonService.Dimension.SOURCE, null, null, false, null, null, false);
        all.addAll(sourceInsights);

        Set<String> realSources = sourceInsights.stream()
                .map(Insight::dimensionValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String source : realSources) {
            List<Insight> campaignInsights = compareBothPeriods(
                    workspaceId, projectId, weekStart, weekEnd, priorWeekStart, priorWeekEnd,
                    SourceComparisonService.Dimension.CAMPAIGN, source, null, false, source, null, false);
            all.addAll(campaignInsights);

            Set<String> campaigns = campaignInsights.stream()
                    .map(Insight::dimensionValue)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean hasCampaignMissingBucket =
                    campaignInsights.stream().anyMatch(i -> "NONE".equals(i.dimensionBucket()));

            for (String campaign : campaigns) {
                all.addAll(compareBothPeriods(
                        workspaceId, projectId, weekStart, weekEnd, priorWeekStart, priorWeekEnd,
                        SourceComparisonService.Dimension.LANDING_PAGE, source, campaign, false, source, campaign,
                        false));
            }
            if (hasCampaignMissingBucket) {
                all.addAll(compareBothPeriods(
                        workspaceId, projectId, weekStart, weekEnd, priorWeekStart, priorWeekEnd,
                        SourceComparisonService.Dimension.LANDING_PAGE, source, null, true, source, null, true));
            }
        }

        Map<String, List<Insight>> byCurrency = all.stream()
                .collect(Collectors.groupingBy(
                        i -> i.currentEvidenceFilters().currency(), LinkedHashMap::new, Collectors.toList()));
        List<CurrencySection> sections = byCurrency.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new CurrencySection(e.getKey(), sortInsights(e.getValue())))
                .toList();

        return new WeeklySummaryResponse(
                workspaceId, projectId, timezone, weekStart, weekEnd, priorWeekStart, priorWeekEnd,
                clock.instant().atOffset(ZoneOffset.UTC), sections);
    }

    private List<Insight> compareBothPeriods(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime currentFrom,
            OffsetDateTime currentTo,
            OffsetDateTime priorFrom,
            OffsetDateTime priorTo,
            SourceComparisonService.Dimension dimension,
            String source,
            String campaign,
            boolean campaignMissing,
            String parentSource,
            String parentCampaign,
            boolean parentCampaignMissing) {
        List<SourceComparisonService.ComparisonRow> current = comparisonService.compare(
                workspaceId, projectId, currentFrom, currentTo, dimension, source, campaign, campaignMissing);
        List<SourceComparisonService.ComparisonRow> prior = comparisonService.compare(
                workspaceId, projectId, priorFrom, priorTo, dimension, source, campaign, campaignMissing);
        return mergeInsights(
                current, prior, workspaceId, projectId, dimension, currentFrom, currentTo, priorFrom, priorTo,
                parentSource, parentCampaign, parentCampaignMissing);
    }

    private static List<Insight> mergeInsights(
            List<SourceComparisonService.ComparisonRow> current,
            List<SourceComparisonService.ComparisonRow> prior,
            UUID workspaceId,
            UUID projectId,
            SourceComparisonService.Dimension dimension,
            OffsetDateTime currentFrom,
            OffsetDateTime currentTo,
            OffsetDateTime priorFrom,
            OffsetDateTime priorTo,
            String parentSource,
            String parentCampaign,
            boolean parentCampaignMissing) {
        record Key(String dimensionValue, boolean attributed, String currency, String movementType) {}
        Map<Key, long[]> totals = new LinkedHashMap<>();
        for (SourceComparisonService.ComparisonRow row : current) {
            Key key = new Key(row.dimensionValue(), row.attributed(), row.currency(), row.movementType());
            totals.computeIfAbsent(key, k -> new long[4]);
            totals.get(key)[0] = row.totalMinor();
            totals.get(key)[1] = row.customerCount();
        }
        for (SourceComparisonService.ComparisonRow row : prior) {
            Key key = new Key(row.dimensionValue(), row.attributed(), row.currency(), row.movementType());
            totals.computeIfAbsent(key, k -> new long[4]);
            totals.get(key)[2] = row.totalMinor();
            totals.get(key)[3] = row.customerCount();
        }

        List<Insight> insights = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            Key key = entry.getKey();
            long[] t = entry.getValue();
            long currentAmount = t[0];
            long currentCount = t[1];
            long priorAmount = t[2];
            long priorCount = t[3];
            String bucket = key.dimensionValue() != null ? null : (key.attributed() ? "NONE" : "UNATTRIBUTED");
            Double percentageChange =
                    priorAmount == 0 ? null : (double) (currentAmount - priorAmount) / priorAmount;

            long applicableCount;
            String status;
            if (priorAmount == 0) {
                applicableCount = currentCount;
            } else if (currentAmount == 0) {
                applicableCount = priorCount;
            } else {
                applicableCount = Math.min(currentCount, priorCount);
            }
            if (applicableCount < LOW_VOLUME_THRESHOLD) {
                status = "INSUFFICIENT_SAMPLE";
            } else if (priorAmount == 0) {
                status = "NEWLY_APPEARED";
            } else if (currentAmount == 0) {
                status = "DISAPPEARED";
            } else if (Math.abs(percentageChange) >= MATERIAL_CHANGE_THRESHOLD) {
                status = "MATERIAL_CHANGE";
            } else {
                status = "STABLE";
            }

            EvidenceFilters currentFilters = evidenceFilters(
                    dimension, key.dimensionValue(), bucket, key.movementType(), key.currency(), currentFrom,
                    currentTo, parentSource, parentCampaign, parentCampaignMissing);
            EvidenceFilters priorFilters = evidenceFilters(
                    dimension, key.dimensionValue(), bucket, key.movementType(), key.currency(), priorFrom, priorTo,
                    parentSource, parentCampaign, parentCampaignMissing);

            insights.add(new Insight(
                    dimension.name(),
                    key.dimensionValue(),
                    bucket,
                    key.movementType(),
                    currentAmount,
                    currentCount,
                    priorAmount,
                    priorCount,
                    percentageChange,
                    applicableCount,
                    status,
                    currentFilters,
                    priorFilters,
                    evidenceLink(workspaceId, projectId, currentFilters),
                    evidenceLink(workspaceId, projectId, priorFilters)));
        }
        return insights;
    }

    private static EvidenceFilters evidenceFilters(
            SourceComparisonService.Dimension dimension,
            String dimensionValue,
            String bucket,
            String movementType,
            String currency,
            OffsetDateTime from,
            OffsetDateTime to,
            String parentSource,
            String parentCampaign,
            boolean parentCampaignMissing) {
        return switch (dimension) {
            case SOURCE -> new EvidenceFilters(
                    from, to, movementType, currency,
                    dimensionValue, "UNATTRIBUTED".equals(bucket), "NONE".equals(bucket),
                    null, false, null, false);
            case CAMPAIGN -> new EvidenceFilters(
                    from, to, movementType, currency,
                    parentSource, false, false,
                    dimensionValue, "NONE".equals(bucket),
                    null, false);
            case LANDING_PAGE -> new EvidenceFilters(
                    from, to, movementType, currency,
                    parentSource, false, false,
                    parentCampaign, parentCampaignMissing,
                    dimensionValue, "NONE".equals(bucket));
        };
    }

    private static String evidenceLink(UUID workspaceId, UUID projectId, EvidenceFilters f) {
        return EvidenceLink.path(
                workspaceId, projectId, f.from(), f.to(), f.movementType(), f.currency(),
                f.source(), f.sourceUnattributed(), f.sourceMissing(),
                f.campaign(), f.campaignMissing(),
                f.landingPage(), f.landingPageMissing());
    }

    /**
     * Deterministic order: dimension (SOURCE, CAMPAIGN, LANDING_PAGE), then parent source/campaign
     * hierarchy, then dimension value (real values before {@code NONE} before {@code UNATTRIBUTED}),
     * then movement type (NEW before CHURN) -- matches the {@code comparison-v1} CSV row order.
     */
    private static List<Insight> sortInsights(List<Insight> insights) {
        Comparator<Insight> byDimension = Comparator.comparingInt(i -> dimensionRank(i.dimension()));
        Comparator<Insight> byParent =
                Comparator.comparing(WeeklySummaryService::parentKey, Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<Insight> byValue =
                Comparator.comparing(WeeklySummaryService::valueSortKey, Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<Insight> byMovementType = Comparator.comparingInt(i -> "NEW".equals(i.movementType()) ? 0 : 1);
        return insights.stream()
                .sorted(byDimension.thenComparing(byParent).thenComparing(byValue).thenComparing(byMovementType))
                .toList();
    }

    private static int dimensionRank(String dimension) {
        return switch (dimension) {
            case "SOURCE" -> 0;
            case "CAMPAIGN" -> 1;
            default -> 2;
        };
    }

    private static String parentKey(Insight insight) {
        EvidenceFilters f = insight.currentEvidenceFilters();
        return switch (insight.dimension()) {
            case "SOURCE" -> "";
            case "CAMPAIGN" -> String.valueOf(f.source());
            default -> f.source() + " " + (f.campaignMissing() ? "MISSING" : f.campaign());
        };
    }

    private static String valueSortKey(Insight insight) {
        if (insight.dimensionValue() != null) {
            return "0" + insight.dimensionValue();
        }
        return "NONE".equals(insight.dimensionBucket()) ? "1" : "2";
    }

    public record WeeklySummaryResponse(
            UUID workspaceId,
            UUID projectId,
            String timezone,
            OffsetDateTime weekStart,
            OffsetDateTime weekEnd,
            OffsetDateTime priorWeekStart,
            OffsetDateTime priorWeekEnd,
            OffsetDateTime generatedAt,
            List<CurrencySection> currencySections) {}

    public record CurrencySection(String currency, List<Insight> insights) {}

    /**
     * {@code dimensionBucket} is {@code NONE} (evidence exists, this field wasn't captured),
     * {@code UNATTRIBUTED} (no evidence at all, SOURCE only), or {@code null} when {@code
     * dimensionValue} carries a real captured value -- the same three-state contract as the CSV
     * exports. {@code percentageChange} is {@code null} exactly when {@code priorAmountMinor = 0}
     * (mathematically undefined); a zero <em>current</em> amount against a nonzero prior yields
     * {@code -1.0}, not {@code null}.
     */
    public record Insight(
            String dimension,
            String dimensionValue,
            String dimensionBucket,
            String movementType,
            long currentAmountMinor,
            long currentCustomerCount,
            long priorAmountMinor,
            long priorCustomerCount,
            Double percentageChange,
            long applicableCustomerCount,
            String status,
            EvidenceFilters currentEvidenceFilters,
            EvidenceFilters priorEvidenceFilters,
            String currentEvidenceLink,
            String priorEvidenceLink) {}

    /** The exact filter contract {@code RevenueMovementsService}/the {@code /movements} endpoint accepts. */
    public record EvidenceFilters(
            OffsetDateTime from,
            OffsetDateTime to,
            String movementType,
            String currency,
            String source,
            boolean sourceUnattributed,
            boolean sourceMissing,
            String campaign,
            boolean campaignMissing,
            String landingPage,
            boolean landingPageMissing) {}
}
