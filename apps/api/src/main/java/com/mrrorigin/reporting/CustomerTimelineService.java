package com.mrrorigin.reporting;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionApplicationService;
import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.attribution.CustomerAttributionExplanation;
import com.mrrorigin.attribution.CustomerLinkRepairAuditService;
import com.mrrorigin.revenue.RevenueCalculationService;
import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Read-only, workspace/project-scoped single-customer detail and evidence timeline for #24: "every
 * customer-level revenue claim can be audited from acquisition touch through identity and
 * subscription movements." Composed entirely from already-derived/normalized tables owned by other
 * modules -- {@code billing_customers}/{@code billing_subscriptions}/{@code
 * billing_subscription_status_events} (billing), {@code stripe_customer_links} (identity), {@code
 * customer_mrr_movements}/{@code customer_mrr_snapshots} (revenue), and {@code
 * customer_attribution_results} plus {@link AttributionApplicationService#explanations} (attribution,
 * reused verbatim rather than re-deriving evidence). This service introduces no new MRR or
 * attribution calculation rule and never reads raw provider JSON.
 *
 * <p>Ownership: identical {@link RevenueMovementsService#OWNER_CTE} rule as every other reporting
 * read model -- a customer not currently owned by {@code projectId} (no active link and no
 * calculated result under it) resolves to {@link Optional#empty()}, which the controller turns into a
 * 404, exactly matching a genuinely unknown customer so existence is never leaked cross-project.
 *
 * <p>Timeline pagination is a documented keyset -- {@code (at, event type rank, reference id)}
 * ascending -- applied in memory after assembling one customer's complete event set. A single
 * customer's lifetime event count (links, touchpoints, status transitions, movements, one attribution
 * calculation, repairs) is bounded by real usage, not by dataset size, so this keeps the identical
 * cursor contract (opaque, stable, gap-free across equal timestamps) as the workspace-wide listings
 * without a multi-source SQL UNION.
 */
@Service
public class CustomerTimelineService {
    private static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private static final int MAX_REPAIR_HISTORY = 500;

    /**
     * Explicit, documented tie-break rank for timeline entries sharing the same timestamp -- the
     * second key of the required "timestamp, event-type rank, then stable ID" ordering. Order is
     * arbitrary but stable: it groups identity evidence before the touchpoints it made eligible,
     * before the billing/revenue facts that followed, before the attribution decision computed from
     * them, before any later manual correction.
     */
    private static final List<String> EVENT_TYPE_RANK = List.of(
            "IDENTITY_LINK_CREATED",
            "TOUCHPOINT_FIRST",
            "TOUCHPOINT_FIRST_AND_LAST",
            "TOUCHPOINT_LAST",
            "SUBSCRIPTION_STATUS_CHANGED",
            "MRR_MOVEMENT",
            "ATTRIBUTION_CALCULATED",
            "IDENTITY_LINK_SUPERSEDED",
            "REPAIR_AUDIT");

    private final JdbcClient db;
    private final AttributionApplicationService attribution;
    private final CustomerLinkRepairAuditService repairAudit;
    private final WorkspaceContext workspaceContext;

    public CustomerTimelineService(
            JdbcClient db,
            AttributionApplicationService attribution,
            CustomerLinkRepairAuditService repairAudit,
            WorkspaceContext workspaceContext) {
        this.db = db;
        this.attribution = attribution;
        this.repairAudit = repairAudit;
        this.workspaceContext = workspaceContext;
    }

    public Optional<CustomerTimeline> get(
            UUID workspaceId, UUID projectId, String stripeCustomerId, String cursor, Integer limit) {
        if (workspaceId == null || projectId == null || stripeCustomerId == null || stripeCustomerId.isBlank()) {
            throw new IllegalArgumentException("workspace, project and customer are required");
        }
        int pageSize = normalizeLimit(limit);

        Optional<BillingCustomerRow> billingCustomer = billingCustomer(workspaceId, projectId, stripeCustomerId);
        if (billingCustomer.isEmpty()) {
            return Optional.empty();
        }

        List<MovementRow> movements = movements(workspaceId, stripeCustomerId);
        List<CustomerAttributionExplanation> explanations =
                attribution.explanations(workspaceId, projectId, stripeCustomerId, AttributionV1Engine.MODEL_VERSION);
        Map<UUID, CustomerAttributionExplanation> explanationByMovement =
                explanations.stream().collect(java.util.stream.Collectors.toMap(CustomerAttributionExplanation::movementId, e -> e));
        Optional<CustomerAttributionExplanation> acquisitionExplanation = explanations.stream()
                .filter(e -> e.movementId().equals(e.acquisitionMovementId()))
                .findFirst();

        List<SubscriptionSummary> subscriptions = subscriptions(workspaceId, stripeCustomerId);
        List<CustomerDirectoryService.CurrentMrrByCurrency> currentMrr = currentMrr(workspaceId, stripeCustomerId);
        List<LinkRow> links = links(workspaceId, projectId, stripeCustomerId);
        List<StatusEventRow> statusEvents = statusEvents(workspaceId, stripeCustomerId);
        List<CustomerLinkRepairAuditService.AuditEntry> repairs =
                repairAudit.history(workspaceId, projectId, stripeCustomerId, MAX_REPAIR_HISTORY);

        Map<UUID, OffsetDateTime> touchpointOccurredAt =
                touchpointOccurredAt(workspaceId, projectId, acquisitionExplanation.orElse(null));
        AcquisitionSummary acquisitionSummary =
                acquisitionSummary(movements, acquisitionExplanation, touchpointOccurredAt);
        ActiveLink activeLink = links.stream()
                .filter(l -> l.supersededAt() == null)
                .findFirst()
                .map(l -> new ActiveLink(l.id(), l.externalUserId(), l.evidenceSource(), l.linkedBySubjectId(), l.createdAt()))
                .orElse(null);
        RepairCapability repairCapability = repairCapability(workspaceId);

        CustomerDetail detail = new CustomerDetail(
                stripeCustomerId,
                billingCustomer.get().deleted(),
                billingCustomer.get().providerCreatedAt(),
                subscriptions,
                currentMrr,
                acquisitionSummary,
                activeLink,
                repairCapability);

        List<TimelineEntry> entries = new ArrayList<>();
        for (LinkRow link : links) {
            entries.add(new TimelineEntry(
                    "IDENTITY_LINK_CREATED",
                    link.createdAt(),
                    link.id(),
                    "Linked to application user " + link.externalUserId() + " (" + link.evidenceSource() + ").",
                    null, null, null, null, null, null, null, null, null, null, null, null, link.externalUserId()));
            if (link.supersededAt() != null) {
                entries.add(new TimelineEntry(
                        "IDENTITY_LINK_SUPERSEDED",
                        link.supersededAt(),
                        link.id(),
                        "Link to " + link.externalUserId() + " was replaced by a repair.",
                        null, null, null, null, null, null, null, null, null, null, null, null, link.externalUserId()));
            }
        }
        entries.addAll(touchpointEntries(acquisitionExplanation.orElse(null), touchpointOccurredAt));
        for (StatusEventRow event : statusEvents) {
            entries.add(new TimelineEntry(
                    "SUBSCRIPTION_STATUS_CHANGED",
                    event.createdAt(),
                    event.id(),
                    "Subscription status recorded as " + event.newStatus()
                            + (event.previousStatus() == null ? " (first observed)." : " (was " + event.previousStatus() + ")."),
                    null, null, null, null, null, null, event.previousStatus(), event.newStatus(),
                    null, null, null, null, null));
        }
        for (MovementRow movement : movements) {
            CustomerAttributionExplanation explanation = explanationByMovement.get(movement.id());
            entries.add(new TimelineEntry(
                    "MRR_MOVEMENT",
                    movement.effectiveAt(),
                    movement.id(),
                    movementExplanation(movement),
                    movement.currency(), movement.amountMinor(), movement.movementType(),
                    null, null, null, null, null,
                    explanation == null ? null : explanation.confidence(),
                    explanation == null ? null : explanation.unattributedReason(),
                    null, null, null));
        }
        acquisitionExplanation.ifPresent(explanation -> entries.add(new TimelineEntry(
                "ATTRIBUTION_CALCULATED",
                explanation.calculatedAt(),
                explanation.movementId(),
                "Attribution calculated (" + explanation.modelVersion() + "): " + explanation.confidence()
                        + (explanation.unattributedReason() == null ? "." : " (" + explanation.unattributedReason() + ")."),
                null, null, null, null, null, null, null, null,
                explanation.confidence(), explanation.unattributedReason(), explanation.modelVersion(), null, null)));
        for (CustomerLinkRepairAuditService.AuditEntry repair : repairs) {
            entries.add(new TimelineEntry(
                    "REPAIR_AUDIT",
                    repair.createdAt(),
                    repair.id(),
                    repairExplanation(repair, stripeCustomerId),
                    null, null, null, null, null, null, null, null, null, null, null,
                    repair.actionType(), repair.externalUserId()));
        }

        entries.sort(Comparator.comparing(TimelineEntry::at, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(e -> rankOf(e.eventType()))
                .thenComparing(e -> e.referenceId().toString()));

        Optional<EntryCursor> decoded = EntryCursor.decode(cursor);
        List<TimelineEntry> afterCursor = decoded.isEmpty()
                ? entries
                : entries.stream().filter(e -> isAfter(e, decoded.get())).toList();
        boolean hasMore = afterCursor.size() > pageSize;
        List<TimelineEntry> page = hasMore ? afterCursor.subList(0, pageSize) : afterCursor;
        String nextCursor = hasMore ? EntryCursor.of(page.getLast()).encode() : null;

        return Optional.of(new CustomerTimeline(detail, page, nextCursor));
    }

    private static boolean isAfter(TimelineEntry entry, EntryCursor cursor) {
        int atComparison = Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())
                .compare(entry.at(), cursor.at());
        if (atComparison != 0) return atComparison > 0;
        int rankComparison = Integer.compare(rankOf(entry.eventType()), cursor.rank());
        if (rankComparison != 0) return rankComparison > 0;
        return entry.referenceId().toString().compareTo(cursor.referenceId()) > 0;
    }

    private static int rankOf(String eventType) {
        int index = EVENT_TYPE_RANK.indexOf(eventType);
        if (index < 0) {
            throw new IllegalStateException("unranked timeline event type: " + eventType);
        }
        return index;
    }

    private static String movementExplanation(MovementRow movement) {
        return switch (movement.movementType()) {
            case "NEW" -> "Became a paying customer.";
            case "EXPANSION" -> "MRR expanded.";
            case "CONTRACTION" -> "MRR contracted.";
            case "CHURN" -> "MRR churned to zero.";
            case "REACTIVATION" -> "MRR reactivated after reaching zero.";
            default -> "MRR movement recorded.";
        };
    }

    private static String repairExplanation(CustomerLinkRepairAuditService.AuditEntry repair, String stripeCustomerId) {
        if (stripeCustomerId.equals(repair.displacedCustomerId())) {
            return "Lost its identity link when " + repair.stripeCustomerId() + " was linked to "
                    + repair.externalUserId() + " instead.";
        }
        return ("CREATED".equals(repair.actionType()) ? "Manually linked to " : "Manually corrected to link to ")
                + repair.externalUserId() + ".";
    }

    private AcquisitionSummary acquisitionSummary(
            List<MovementRow> movements,
            Optional<CustomerAttributionExplanation> acquisitionExplanation,
            Map<UUID, OffsetDateTime> touchpointOccurredAt) {
        Optional<MovementRow> acquisitionMovement = movements.stream()
                .filter(m -> "NEW".equals(m.movementType()))
                .min(Comparator.comparing(MovementRow::effectiveAt).thenComparing(MovementRow::id));
        if (acquisitionMovement.isEmpty()) {
            return new AcquisitionSummary(
                    null, null, AttributionV1Engine.MODEL_VERSION, "NO_ACQUISITION_MOVEMENT",
                    null, null, null, null, List.of(), null);
        }
        if (acquisitionExplanation.isEmpty()) {
            return new AcquisitionSummary(
                    acquisitionMovement.get().id(), acquisitionMovement.get().effectiveAt(),
                    AttributionV1Engine.MODEL_VERSION, "NOT_RECALCULATED",
                    null, null, null, null, List.of(), null);
        }
        CustomerAttributionExplanation explanation = acquisitionExplanation.get();
        return new AcquisitionSummary(
                explanation.movementId(),
                explanation.movementAt(),
                explanation.modelVersion(),
                explanation.confidence(),
                explanation.unattributedReason(),
                touchSummary(explanation.firstTouch(), touchpointOccurredAt),
                touchSummary(explanation.lastTouch(), touchpointOccurredAt),
                explanation.customerLinkEvidenceId(),
                explanation.sourceReferences(),
                explanation.calculatedAt());
    }

    private static TouchSummary touchSummary(
            CustomerAttributionExplanation.Evidence evidence, Map<UUID, OffsetDateTime> touchpointOccurredAt) {
        if (evidence == null) {
            return null;
        }
        return new TouchSummary(
                evidence.touchpointId(),
                touchpointOccurredAt.get(evidence.touchpointId()),
                evidence.source(),
                evidence.campaign(),
                evidence.landingPage());
    }

    /** Workspace/project-scoped {@code occurred_at} lookup for the acquisition's first/last touch evidence. */
    private Map<UUID, OffsetDateTime> touchpointOccurredAt(
            UUID workspaceId, UUID projectId, CustomerAttributionExplanation acquisitionExplanation) {
        if (acquisitionExplanation == null) {
            return Map.of();
        }
        CustomerAttributionExplanation.Evidence first = acquisitionExplanation.firstTouch();
        CustomerAttributionExplanation.Evidence last = acquisitionExplanation.lastTouch();
        List<UUID> ids = new ArrayList<>();
        if (first != null) ids.add(first.touchpointId());
        if (last != null && (first == null || !last.touchpointId().equals(first.touchpointId()))) {
            ids.add(last.touchpointId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return db.sql(
                        "SELECT id, occurred_at FROM touchpoints WHERE workspace_id = :w AND project_id = :p AND id = ANY(:ids)")
                .param("w", workspaceId)
                .param("p", projectId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class)))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<TimelineEntry> touchpointEntries(
            CustomerAttributionExplanation acquisitionExplanation, Map<UUID, OffsetDateTime> touchpointOccurredAt) {
        if (acquisitionExplanation == null) {
            return List.of();
        }
        CustomerAttributionExplanation.Evidence first = acquisitionExplanation.firstTouch();
        CustomerAttributionExplanation.Evidence last = acquisitionExplanation.lastTouch();
        if (first == null && last == null) {
            return List.of();
        }

        List<TimelineEntry> result = new ArrayList<>();
        boolean sameTouchpoint = first != null && last != null && first.touchpointId().equals(last.touchpointId());
        if (first != null) {
            OffsetDateTime at = touchpointOccurredAt.get(first.touchpointId());
            String type = sameTouchpoint ? "TOUCHPOINT_FIRST_AND_LAST" : "TOUCHPOINT_FIRST";
            String label = sameTouchpoint ? "Only known touchpoint (first and last touch)." : "First touch.";
            result.add(new TimelineEntry(
                    type, at, first.touchpointId(),
                    label + describeTouch(first),
                    null, null, null, first.source(), first.campaign(), first.landingPage(),
                    null, null, null, null, null, null, null));
        }
        if (last != null && !sameTouchpoint) {
            OffsetDateTime at = touchpointOccurredAt.get(last.touchpointId());
            result.add(new TimelineEntry(
                    "TOUCHPOINT_LAST", at, last.touchpointId(),
                    "Last touch." + describeTouch(last),
                    null, null, null, last.source(), last.campaign(), last.landingPage(),
                    null, null, null, null, null, null, null));
        }
        return result;
    }

    private static String describeTouch(CustomerAttributionExplanation.Evidence evidence) {
        if (evidence.source() == null && evidence.campaign() == null) {
            return " Direct traffic.";
        }
        StringBuilder sb = new StringBuilder(" Source: ").append(evidence.source() == null ? "unknown" : evidence.source());
        if (evidence.campaign() != null) {
            sb.append(", campaign: ").append(evidence.campaign());
        }
        return sb.toString();
    }

    private RepairCapability repairCapability(UUID workspaceId) {
        boolean canManage = workspaceContext.canManage(workspaceId);
        return canManage
                ? new RepairCapability(true, null)
                : new RepairCapability(false, "WORKSPACE_ROLE_INSUFFICIENT");
    }

    private Optional<BillingCustomerRow> billingCustomer(UUID workspaceId, UUID projectId, String stripeCustomerId) {
        return db.sql(
                        "WITH " + RevenueMovementsService.OWNER_CTE
                                + """
                        SELECT bc.deleted, bc.provider_created_at
                        FROM billing_customers bc
                        JOIN owner o ON o.customer_id = bc.stripe_customer_id AND o.owning_project_id = :p
                        WHERE bc.workspace_id = :w AND bc.stripe_customer_id = :c
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("c", stripeCustomerId)
                .query((rs, n) -> new BillingCustomerRow(
                        rs.getBoolean("deleted"), rs.getObject("provider_created_at", OffsetDateTime.class)))
                .optional();
    }

    private List<MovementRow> movements(UUID workspaceId, String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT id, currency, amount_minor, movement_type, effective_at
                        FROM customer_mrr_movements
                        WHERE workspace_id = :w AND stripe_customer_id = :c AND calculation_version = :cv
                        ORDER BY effective_at, id
                        """)
                .param("w", workspaceId)
                .param("c", stripeCustomerId)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .query((rs, n) -> new MovementRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("currency"),
                        rs.getLong("amount_minor"),
                        rs.getString("movement_type"),
                        rs.getObject("effective_at", OffsetDateTime.class)))
                .list();
    }

    private List<CustomerDirectoryService.CurrentMrrByCurrency> currentMrr(UUID workspaceId, String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT DISTINCT ON (currency) currency, amount_minor
                        FROM customer_mrr_snapshots
                        WHERE workspace_id = :w AND stripe_customer_id = :c AND calculation_version = :cv
                          AND supported = TRUE
                        ORDER BY currency, effective_at DESC
                        """)
                .param("w", workspaceId)
                .param("c", stripeCustomerId)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .query((rs, n) -> new CustomerDirectoryService.CurrentMrrByCurrency(
                        rs.getString("currency"), rs.getLong("amount_minor")))
                .list();
    }

    private List<SubscriptionSummary> subscriptions(UUID workspaceId, String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT stripe_subscription_id, status, currency, current_period_start, current_period_end,
                          cancel_at_period_end, cancel_at, canceled_at, trial_start, trial_end
                        FROM billing_subscriptions
                        WHERE workspace_id = :w AND stripe_customer_id = :c
                        ORDER BY created_at
                        """)
                .param("w", workspaceId)
                .param("c", stripeCustomerId)
                .query((rs, n) -> new SubscriptionSummary(
                        rs.getString("stripe_subscription_id"),
                        rs.getString("status"),
                        rs.getString("currency"),
                        rs.getObject("current_period_start", OffsetDateTime.class),
                        rs.getObject("current_period_end", OffsetDateTime.class),
                        rs.getBoolean("cancel_at_period_end"),
                        rs.getObject("cancel_at", OffsetDateTime.class),
                        rs.getObject("canceled_at", OffsetDateTime.class),
                        rs.getObject("trial_start", OffsetDateTime.class),
                        rs.getObject("trial_end", OffsetDateTime.class)))
                .list();
    }

    private List<LinkRow> links(UUID workspaceId, UUID projectId, String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT l.id, l.evidence_source, l.linked_by_subject_id, l.superseded_at, l.created_at,
                               i.external_user_id
                        FROM stripe_customer_links l
                        JOIN external_identities i
                          ON i.id = l.external_identity_id AND i.workspace_id = l.workspace_id AND i.project_id = l.project_id
                        WHERE l.workspace_id = :w AND l.project_id = :p AND l.stripe_customer_id = :c
                        ORDER BY l.created_at
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("c", stripeCustomerId)
                .query((rs, n) -> new LinkRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("evidence_source"),
                        rs.getString("linked_by_subject_id"),
                        rs.getObject("superseded_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("external_user_id")))
                .list();
    }

    private List<StatusEventRow> statusEvents(UUID workspaceId, String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT e.id, e.previous_status, e.new_status, e.created_at
                        FROM billing_subscription_status_events e
                        JOIN billing_subscriptions s ON s.workspace_id = e.workspace_id AND s.id = e.subscription_id
                        WHERE e.workspace_id = :w AND s.stripe_customer_id = :c
                        ORDER BY e.created_at
                        """)
                .param("w", workspaceId)
                .param("c", stripeCustomerId)
                .query((rs, n) -> new StatusEventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("previous_status"),
                        rs.getString("new_status"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private static int normalizeLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }

    private record BillingCustomerRow(boolean deleted, OffsetDateTime providerCreatedAt) {}

    private record MovementRow(UUID id, String currency, long amountMinor, String movementType, OffsetDateTime effectiveAt) {}

    private record LinkRow(
            UUID id,
            String evidenceSource,
            String linkedBySubjectId,
            OffsetDateTime supersededAt,
            OffsetDateTime createdAt,
            String externalUserId) {}

    private record StatusEventRow(UUID id, String previousStatus, String newStatus, OffsetDateTime createdAt) {}

    /** Opaque, stable keyset cursor over {@code (at, event type rank, reference id)}. */
    private record EntryCursor(OffsetDateTime at, int rank, String referenceId) {
        String encode() {
            String raw = at.toInstant() + "|" + rank + "|" + referenceId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static EntryCursor of(TimelineEntry entry) {
            return new EntryCursor(entry.at(), rankOf(entry.eventType()), entry.referenceId().toString());
        }

        static Optional<EntryCursor> decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return Optional.empty();
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("malformed cursor");
                }
                return Optional.of(new EntryCursor(OffsetDateTime.parse(parts[0]), Integer.parseInt(parts[1]), parts[2]));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("malformed cursor", malformed);
            }
        }
    }

    public record CustomerTimeline(CustomerDetail detail, List<TimelineEntry> entries, String nextCursor) {}

    public record CustomerDetail(
            String stripeCustomerId,
            boolean deleted,
            OffsetDateTime providerCreatedAt,
            List<SubscriptionSummary> subscriptions,
            List<CustomerDirectoryService.CurrentMrrByCurrency> currentMrr,
            AcquisitionSummary acquisition,
            ActiveLink activeLink,
            RepairCapability repairCapability) {}

    public record SubscriptionSummary(
            String stripeSubscriptionId,
            String status,
            String currency,
            OffsetDateTime currentPeriodStart,
            OffsetDateTime currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            OffsetDateTime cancelAt,
            OffsetDateTime canceledAt,
            OffsetDateTime trialStart,
            OffsetDateTime trialEnd) {}

    /**
     * {@code status} is one of {@code STRONG}, {@code UNATTRIBUTED}, {@code NOT_RECALCULATED} (a
     * {@code NEW} movement exists but has no stored result under the current model version yet -- an
     * operational gap, not a negative result), or {@code NO_ACQUISITION_MOVEMENT} (the customer has
     * never had a {@code NEW} movement). {@code movementId}/{@code effectiveAt} are null only for
     * {@code NO_ACQUISITION_MOVEMENT}.
     */
    public record AcquisitionSummary(
            UUID movementId,
            OffsetDateTime effectiveAt,
            String modelVersion,
            String status,
            String unattributedReason,
            TouchSummary firstTouch,
            TouchSummary lastTouch,
            UUID customerLinkEvidenceId,
            List<String> sourceReferences,
            OffsetDateTime calculatedAt) {}

    public record TouchSummary(UUID touchpointId, OffsetDateTime occurredAt, String source, String campaign, String landingPage) {}

    public record ActiveLink(
            UUID id, String externalUserId, String evidenceSource, String linkedBySubjectId, OffsetDateTime createdAt) {}

    /** {@code reason} is populated only when {@code canRepair} is false. */
    public record RepairCapability(boolean canRepair, String reason) {}

    /**
     * One evidence-timeline row. {@code eventType} is a stable machine-readable identifier (see {@link
     * #EVENT_TYPE_RANK} for the complete set); {@code at}/{@code referenceId}/{@code explanation} are
     * always populated. Every other field is populated only when relevant to that event type, and is
     * otherwise null -- never a fabricated default.
     */
    public record TimelineEntry(
            String eventType,
            OffsetDateTime at,
            UUID referenceId,
            String explanation,
            String currency,
            Long amountMinor,
            String movementType,
            String source,
            String campaign,
            String landingPage,
            String previousStatus,
            String newStatus,
            String confidence,
            String unattributedReason,
            String modelVersion,
            String actionType,
            String externalUserId) {}
}
