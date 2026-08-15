package com.mrrorigin.reporting;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.revenue.RevenueCalculationService;

/**
 * Read-only, paginated MRR movement listing with attached attribution evidence (#22). This is the
 * drill-down target for every summarized number {@link RevenueOverviewService} produces: each
 * movement carries its confidence, source/campaign/landing-page evidence, or unattributed reason,
 * so a founder can trace a summarized claim back to the customer and evidence that produced it.
 *
 * <p>Uses the same {@link #OWNER_CTE} project resolution and keyset-pagination approach as {@link
 * UnattributedRevenueInboxService}, generalized to all movement types (not only New MRR).
 */
@Service
public class RevenueMovementsService {
    private static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;
    private static final Set<String> MOVEMENT_TYPES =
            Set.of("NEW", "EXPANSION", "CONTRACTION", "CHURN", "REACTIVATION");

    /**
     * Resolves exactly one "owning project" per Stripe customer, so the same movement can never be
     * counted toward two projects' reporting at once.
     *
     * <p>{@code customer_mrr_movements} carries no {@code project_id} of its own -- a customer's
     * project is only knowable through {@code stripe_customer_links} or {@code
     * customer_attribution_results}. {@link com.mrrorigin.attribution.AttributionApplicationService#recalculate}
     * re-stamps a customer's <em>entire</em> movement history under whichever project recalculates
     * it, so a customer who was linked to project A, then freed and relinked to project B, can end
     * up with {@code customer_attribution_results} rows for the same movement under both A and B
     * once B recalculates. Preferring the customer's currently active link, and otherwise the most
     * recently calculated result (by {@code calculated_at}), always resolves to the customer's
     * current project rather than double-counting into a stale former one.
     */
    static final String OWNER_CTE =
            """
            owner AS (
              SELECT COALESCE(active.customer_id, latest.customer_id) AS customer_id,
                     COALESCE(active.project_id, latest.project_id) AS owning_project_id
              FROM (
                SELECT stripe_customer_id AS customer_id, project_id
                FROM stripe_customer_links
                WHERE workspace_id = :w AND superseded_at IS NULL
              ) active
              FULL OUTER JOIN (
                SELECT DISTINCT ON (m.stripe_customer_id) m.stripe_customer_id AS customer_id, r.project_id
                FROM customer_attribution_results r
                JOIN customer_mrr_movements m ON m.workspace_id = r.workspace_id AND m.id = r.movement_id
                WHERE r.workspace_id = :w
                ORDER BY m.stripe_customer_id, r.calculated_at DESC, r.id DESC
              ) latest ON latest.customer_id = active.customer_id
            )
            """;

    private final JdbcClient db;

    public RevenueMovementsService(JdbcClient db) {
        this.db = db;
    }

    public Page list(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            String movementType,
            String source,
            String campaign,
            String landingPage,
            String currency,
            String cursor,
            Integer limit) {
        if (workspaceId == null || projectId == null || from == null || to == null) {
            throw new IllegalArgumentException("workspace, project, from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (movementType != null && !MOVEMENT_TYPES.contains(movementType)) {
            throw new IllegalArgumentException("movementType must be one of " + MOVEMENT_TYPES);
        }
        if ((campaign != null || landingPage != null) && source == null) {
            throw new IllegalArgumentException("source is required when filtering by campaign or landingPage");
        }
        if (landingPage != null && campaign == null) {
            throw new IllegalArgumentException("campaign is required when filtering by landingPage");
        }
        int pageSize = normalizeLimit(limit);
        Optional<Cursor> decoded = Cursor.decode(cursor);

        var rows = db.sql(
                        "WITH " + OWNER_CTE
                                + """
                        SELECT m.id, m.stripe_customer_id, m.currency, m.amount_minor, m.movement_type, m.effective_at,
                          r.confidence, r.unattributed_reason,
                          r.first_source, r.first_campaign, r.first_landing_page,
                          r.last_source, r.last_campaign, r.last_landing_page
                        FROM customer_mrr_movements m
                        JOIN owner o ON o.customer_id = m.stripe_customer_id AND o.owning_project_id = :p
                        LEFT JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE m.workspace_id = :w AND m.calculation_version = :cv
                          AND m.effective_at >= :from AND m.effective_at < :to
                          AND (:hasType = FALSE OR m.movement_type = :type)
                          AND (:hasCurrency = FALSE OR m.currency = :currency)
                          AND (:hasSource = FALSE
                               OR (:source = 'UNATTRIBUTED' AND (r.confidence IS DISTINCT FROM 'STRONG'))
                               OR (r.confidence = 'STRONG' AND r.first_source = :source))
                          AND (:hasCampaign = FALSE
                               OR (:campaign = 'NONE' AND r.confidence = 'STRONG' AND r.first_campaign IS NULL)
                               OR (r.confidence = 'STRONG' AND r.first_campaign = :campaign))
                          AND (:hasLandingPage = FALSE
                               OR (:landingPage = 'NONE' AND r.confidence = 'STRONG' AND r.first_landing_page IS NULL)
                               OR (r.confidence = 'STRONG' AND r.first_landing_page = :landingPage))
                          AND (:hasCursor = FALSE OR (m.effective_at, m.id) > (:cursorAt, :cursorId))
                        ORDER BY m.effective_at, m.id
                        LIMIT :fetchLimit
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("from", from)
                .param("to", to)
                .param("hasType", movementType != null)
                .param("type", movementType == null ? "" : movementType)
                .param("hasCurrency", currency != null)
                .param("currency", currency == null ? "" : currency)
                .param("hasSource", source != null)
                .param("source", source == null ? "" : source)
                .param("hasCampaign", campaign != null)
                .param("campaign", campaign == null ? "" : campaign)
                .param("hasLandingPage", landingPage != null)
                .param("landingPage", landingPage == null ? "" : landingPage)
                .param("hasCursor", decoded.isPresent())
                .param("cursorAt", decoded.map(Cursor::effectiveAt).orElse(OffsetDateTime.now()))
                .param("cursorId", decoded.map(Cursor::movementId).orElse(new UUID(0, 0)))
                .param("fetchLimit", pageSize + 1)
                .query((rs, n) -> new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getString("stripe_customer_id"),
                        rs.getString("currency"),
                        rs.getLong("amount_minor"),
                        rs.getString("movement_type"),
                        rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getString("confidence"),
                        rs.getString("unattributed_reason"),
                        touch(rs.getString("first_source"), rs.getString("first_campaign"), rs.getString("first_landing_page")),
                        touch(rs.getString("last_source"), rs.getString("last_campaign"), rs.getString("last_landing_page"))))
                .list();

        boolean hasMore = rows.size() > pageSize;
        List<Entry> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore
                ? new Cursor(pageRows.getLast().effectiveAt(), pageRows.getLast().movementId()).encode()
                : null;
        return new Page(pageRows, nextCursor);
    }

    private static Touch touch(String source, String campaign, String landingPage) {
        return source == null && campaign == null && landingPage == null ? null : new Touch(source, campaign, landingPage);
    }

    private static int normalizeLimit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }

    /** Opaque, stable keyset cursor over {@code (effective_at, movement_id)} -- never a raw offset. */
    private record Cursor(OffsetDateTime effectiveAt, UUID movementId) {
        String encode() {
            String raw = effectiveAt.toInstant() + "|" + movementId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Optional<Cursor> decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return Optional.empty();
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                if (separator < 0) {
                    throw new IllegalArgumentException("malformed cursor");
                }
                OffsetDateTime at = OffsetDateTime.parse(raw.substring(0, separator));
                UUID id = UUID.fromString(raw.substring(separator + 1));
                return Optional.of(new Cursor(at, id));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("malformed cursor", malformed);
            }
        }
    }

    public record Page(List<Entry> entries, String nextCursor) {}

    /**
     * {@code confidence}/{@code unattributedReason} are null when attribution has not been
     * recalculated for this movement under the current model version yet (operational gap, not a
     * negative attribution result).
     */
    public record Entry(
            UUID movementId,
            String stripeCustomerId,
            String currency,
            long amountMinor,
            String movementType,
            OffsetDateTime effectiveAt,
            String confidence,
            String unattributedReason,
            Touch firstTouch,
            Touch lastTouch) {}

    public record Touch(String source, String campaign, String landingPage) {}
}
