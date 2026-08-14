package com.mrrorigin.reporting;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.attribution.DeterministicRepairSuggestionService;
import com.mrrorigin.revenue.RevenueCalculationService;

/**
 * Read-only, workspace/project-scoped listing of unattributed New MRR customers (#20), matching
 * ARCHITECTURE.md's "Unattributed revenue inbox" reporting outcome: "Stripe customer -> New MRR ->
 * reason -> deterministic repair action". Built from {@code customer_mrr_movements} (revenue),
 * {@code customer_attribution_results} (attribution), and {@code stripe_customer_links} (identity).
 *
 * <p>The candidate scope starts from the same set {@link
 * com.mrrorigin.attribution.AttributionApplicationService#coverage} and the #19 batch recalculation
 * job use (currently linked in this project, or previously recalculated in this project), plus one
 * addition specific to this read model: a Stripe customer with New MRR that has never been linked
 * from *any* project in the workspace. {@code billing_customers}/{@code customer_mrr_movements} carry
 * no {@code project_id} -- a customer's project is only known once something links it -- so an
 * entirely fresh, never-linked customer is surfaced from every project in the workspace until a
 * repair claims it into one. Omitting this case would make the single most common "why is this
 * unattributed" answer (nobody has linked it yet) invisible from the inbox entirely, defeating
 * PRODUCT.md job 4. This addition only widens what this listing shows; it does not change #19's
 * batch recalculation scope or {@code coverage}'s numbers.
 *
 * <p>No new attribution evidence is invented here: the reason codes surfaced
 * ({@code NO_ACTIVE_LINK}, {@code NO_ELIGIBLE_TOUCHPOINT}, {@code NOT_RECALCULATED}) are exactly the
 * stored/derived reasons ADR-0005 and the coverage read model already define. For the never-linked
 * case there is no stored result row to read a reason from, but "no active link" is directly and
 * safely knowable from {@code stripe_customer_links} without running the engine.
 */
@Service
public class UnattributedRevenueInboxService {
    private static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    private final JdbcClient db;
    private final DeterministicRepairSuggestionService suggestions;

    public UnattributedRevenueInboxService(JdbcClient db, DeterministicRepairSuggestionService suggestions) {
        this.db = db;
        this.suggestions = suggestions;
    }

    public Page list(UUID workspaceId, UUID projectId, String cursor, Integer limit) {
        if (workspaceId == null || projectId == null) {
            throw new IllegalArgumentException("workspace and project are required");
        }
        int pageSize = normalizeLimit(limit);
        Optional<Cursor> decoded = Cursor.decode(cursor);

        var rows = db.sql(
                        """
                        WITH candidates AS (
                          SELECT stripe_customer_id AS customer_id
                          FROM stripe_customer_links
                          WHERE workspace_id = :w AND project_id = :p AND superseded_at IS NULL
                          UNION
                          SELECT m.stripe_customer_id AS customer_id
                          FROM customer_attribution_results r
                          JOIN customer_mrr_movements m ON m.workspace_id = r.workspace_id AND m.id = r.movement_id
                          WHERE r.workspace_id = :w AND r.project_id = :p
                          UNION
                          -- Billing customers (workspace-scoped; there is no project_id on
                          -- billing_customers/customer_mrr_movements) with New MRR that have never been
                          -- linked to *any* project in this workspace. Their eventual project is unknown
                          -- until a repair claims them, so they surface from every project in the
                          -- workspace until then -- otherwise the single most important unattributed case
                          -- (a fresh Stripe customer nobody has linked yet) would never be visible or
                          -- repairable from any inbox at all.
                          SELECT m.stripe_customer_id AS customer_id
                          FROM customer_mrr_movements m
                          WHERE m.workspace_id = :w AND m.calculation_version = :cv AND m.movement_type = 'NEW'
                            AND NOT EXISTS (
                                SELECT 1 FROM stripe_customer_links l
                                WHERE l.workspace_id = :w AND l.stripe_customer_id = m.stripe_customer_id
                                  AND l.superseded_at IS NULL
                            )
                        ),
                        acquisitions AS (
                          SELECT DISTINCT ON (m.stripe_customer_id)
                                 m.stripe_customer_id AS customer_id, m.id AS movement_id,
                                 m.effective_at, m.currency, m.amount_minor
                          FROM customer_mrr_movements m
                          JOIN candidates c ON c.customer_id = m.stripe_customer_id
                          WHERE m.workspace_id = :w AND m.calculation_version = :cv AND m.movement_type = 'NEW'
                          ORDER BY m.stripe_customer_id, m.effective_at, m.id
                        )
                        SELECT a.customer_id, a.movement_id, a.effective_at, a.currency, a.amount_minor,
                               r.confidence, r.unattributed_reason,
                               EXISTS (
                                   SELECT 1 FROM stripe_customer_links l
                                   WHERE l.workspace_id = :w AND l.stripe_customer_id = a.customer_id
                                     AND l.superseded_at IS NULL
                               ) AS has_active_link
                        FROM acquisitions a
                        LEFT JOIN customer_attribution_results r
                          ON r.workspace_id = :w AND r.project_id = :p AND r.movement_id = a.movement_id
                             AND r.model_version = :mv
                        WHERE (r.id IS NULL OR r.confidence <> 'STRONG')
                          AND (:hasCursor = FALSE OR (a.effective_at, a.movement_id) > (:cursorAt, :cursorId))
                        ORDER BY a.effective_at, a.movement_id
                        LIMIT :fetchLimit
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("hasCursor", decoded.isPresent())
                .param("cursorAt", decoded.map(Cursor::effectiveAt).orElse(OffsetDateTime.now()))
                .param("cursorId", decoded.map(Cursor::movementId).orElse(new UUID(0, 0)))
                .param("fetchLimit", pageSize + 1)
                .query((rs, rowNum) -> new Row(
                        rs.getString("customer_id"),
                        rs.getObject("movement_id", UUID.class),
                        rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getString("currency"),
                        rs.getLong("amount_minor"),
                        rs.getString("unattributed_reason"),
                        rs.getBoolean("has_active_link")))
                .list();

        boolean hasMore = rows.size() > pageSize;
        List<Row> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<Entry> entries = pageRows.stream()
                .map(row -> toEntry(workspaceId, projectId, row))
                .toList();
        String nextCursor = hasMore
                ? new Cursor(pageRows.getLast().effectiveAt(), pageRows.getLast().movementId()).encode()
                : null;
        return new Page(entries, nextCursor);
    }

    private Entry toEntry(UUID workspaceId, UUID projectId, Row row) {
        // No stored result for the current model version. If a link currently exists (or once
        // produced a result, e.g. under a prior model version), the gap is purely operational --
        // recalculation hasn't run yet. Otherwise this customer has no active link at all, which is
        // knowable directly from stripe_customer_links without needing a stored result to say so.
        String reason;
        if (row.unattributedReason() != null) {
            reason = row.unattributedReason();
        } else if (row.hasActiveLink()) {
            reason = "NOT_RECALCULATED";
        } else {
            reason = "NO_ACTIVE_LINK";
        }
        Suggestion suggestion = "NO_ACTIVE_LINK".equals(reason)
                ? suggestions
                        .suggest(workspaceId, projectId, row.customerId())
                        .map(s -> new Suggestion(s.externalIdentityId(), s.externalUserId(), s.evidenceLinkId()))
                        .orElse(null)
                : null;
        return new Entry(
                row.customerId(),
                row.movementId(),
                row.effectiveAt(),
                row.currency(),
                row.amountMinor(),
                reason,
                AttributionV1Engine.MODEL_VERSION,
                suggestion,
                suggestion == null ? "NO_DETERMINISTIC_REPAIR_AVAILABLE" : null);
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

    private record Row(
            String customerId,
            UUID movementId,
            OffsetDateTime effectiveAt,
            String currency,
            long amountMinor,
            String unattributedReason,
            boolean hasActiveLink) {}

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

    public record Entry(
            String stripeCustomerId,
            UUID acquisitionMovementId,
            OffsetDateTime acquisitionEffectiveAt,
            String currency,
            long amountMinor,
            String reason,
            String modelVersion,
            Suggestion suggestion,
            String suggestionUnavailableReason) {}

    public record Suggestion(UUID externalIdentityId, String externalUserId, UUID evidenceLinkId) {}
}
