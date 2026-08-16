package com.mrrorigin.reporting;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.attribution.AttributionV1Engine;
import com.mrrorigin.revenue.RevenueCalculationService;

/**
 * Read-only, workspace/project-scoped customer directory for #24: one row per Stripe customer this
 * project owns, with just enough summary (acquisition confidence/source, current MRR per currency,
 * subscription statuses) to search and select a customer before opening {@link
 * CustomerTimelineService}'s full evidence timeline.
 *
 * <p>Uses the same {@link RevenueMovementsService#OWNER_CTE} project-resolution rule as every other
 * reporting read model, so a customer is listed under exactly one project -- never duplicated across
 * projects, never shown before something has linked or attributed it into this one.
 *
 * <p>A customer's "acquisition" for list purposes is its earliest {@code NEW} MRR movement, matching
 * {@link com.mrrorigin.attribution.AttributionApplicationService#recalculate}'s own
 * {@code (effective_at, id)}-ordered selection -- a customer can have more than one {@code NEW}
 * movement (ADR-0004: a direct currency switch is churn in the old currency, New in the new one), but
 * only the earliest is "the" acquisition the attribution engine anchors evidence to.
 */
@Service
public class CustomerDirectoryService {
    private static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    private final JdbcClient db;

    public CustomerDirectoryService(JdbcClient db) {
        this.db = db;
    }

    public Page list(UUID workspaceId, UUID projectId, String search, String cursor, Integer limit) {
        if (workspaceId == null || projectId == null) {
            throw new IllegalArgumentException("workspace and project are required");
        }
        int pageSize = normalizeLimit(limit);
        Optional<Cursor> decoded = Cursor.decode(cursor);
        boolean hasSearch = search != null && !search.isBlank();

        var rows = db.sql(
                        "WITH " + RevenueMovementsService.OWNER_CTE
                                + """
                        SELECT bc.stripe_customer_id, bc.deleted, bc.provider_created_at,
                          m.effective_at AS acquisition_at, r.confidence, r.unattributed_reason, r.first_source
                        FROM billing_customers bc
                        JOIN owner o ON o.customer_id = bc.stripe_customer_id AND o.owning_project_id = :p
                        LEFT JOIN LATERAL (
                          SELECT id FROM customer_mrr_movements
                          WHERE workspace_id = bc.workspace_id AND stripe_customer_id = bc.stripe_customer_id
                            AND calculation_version = :cv AND movement_type = 'NEW'
                          ORDER BY effective_at, id
                          LIMIT 1
                        ) am ON TRUE
                        LEFT JOIN customer_mrr_movements m ON m.workspace_id = bc.workspace_id AND m.id = am.id
                        LEFT JOIN customer_attribution_results r
                          ON r.workspace_id = m.workspace_id AND r.project_id = :p AND r.movement_id = m.id
                             AND r.model_version = :mv
                        WHERE bc.workspace_id = :w
                          AND (:hasSearch = FALSE OR bc.stripe_customer_id ILIKE :search)
                          AND (:hasCursor = FALSE
                               OR (bc.provider_created_at, bc.stripe_customer_id) < (:cursorAt, :cursorId))
                        ORDER BY bc.provider_created_at DESC, bc.stripe_customer_id DESC
                        LIMIT :fetchLimit
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .param("mv", AttributionV1Engine.MODEL_VERSION)
                .param("hasSearch", hasSearch)
                .param("search", hasSearch ? "%" + search.trim() + "%" : "")
                .param("hasCursor", decoded.isPresent())
                .param("cursorAt", decoded.map(Cursor::createdAt).orElse(OffsetDateTime.now()))
                .param("cursorId", decoded.map(Cursor::customerId).orElse(""))
                .param("fetchLimit", pageSize + 1)
                .query((rs, n) -> new Row(
                        rs.getString("stripe_customer_id"),
                        rs.getBoolean("deleted"),
                        rs.getObject("provider_created_at", OffsetDateTime.class),
                        rs.getObject("acquisition_at", OffsetDateTime.class),
                        rs.getString("confidence"),
                        rs.getString("unattributed_reason"),
                        rs.getString("first_source")))
                .list();

        boolean hasMore = rows.size() > pageSize;
        List<Row> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<String> customerIds = pageRows.stream().map(Row::customerId).toList();
        Map<String, List<CurrentMrrByCurrency>> mrrByCustomer = currentMrr(workspaceId, customerIds);
        Map<String, List<String>> statusesByCustomer = subscriptionStatuses(workspaceId, customerIds);

        List<Entry> entries = pageRows.stream()
                .map(row -> new Entry(
                        row.customerId(),
                        row.deleted(),
                        row.providerCreatedAt(),
                        row.acquisitionAt(),
                        row.confidence(),
                        row.unattributedReason(),
                        row.firstSource(),
                        mrrByCustomer.getOrDefault(row.customerId(), List.of()),
                        statusesByCustomer.getOrDefault(row.customerId(), List.of())))
                .toList();
        String nextCursor = hasMore
                ? new Cursor(pageRows.getLast().providerCreatedAt(), pageRows.getLast().customerId()).encode()
                : null;
        return new Page(entries, nextCursor);
    }

    private Map<String, List<CurrentMrrByCurrency>> currentMrr(UUID workspaceId, List<String> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<CurrentMrrByCurrency>> byCustomer = new HashMap<>();
        db.sql(
                        """
                        SELECT DISTINCT ON (stripe_customer_id, currency) stripe_customer_id, currency, amount_minor
                        FROM customer_mrr_snapshots
                        WHERE workspace_id = :w AND stripe_customer_id = ANY(:ids) AND calculation_version = :cv
                          AND supported = TRUE
                        ORDER BY stripe_customer_id, currency, effective_at DESC
                        """)
                .param("w", workspaceId)
                .param("ids", customerIds.toArray(String[]::new))
                .param("cv", RevenueCalculationService.CALCULATION_VERSION)
                .query((rs, n) -> Map.entry(
                        rs.getString("stripe_customer_id"),
                        new CurrentMrrByCurrency(rs.getString("currency"), rs.getLong("amount_minor"))))
                .list()
                .forEach(pair -> byCustomer
                        .computeIfAbsent(pair.getKey(), key -> new java.util.ArrayList<>())
                        .add(pair.getValue()));
        return byCustomer;
    }

    private Map<String, List<String>> subscriptionStatuses(UUID workspaceId, List<String> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> byCustomer = new HashMap<>();
        db.sql(
                        """
                        SELECT stripe_customer_id, status
                        FROM billing_subscriptions
                        WHERE workspace_id = :w AND stripe_customer_id = ANY(:ids)
                        ORDER BY stripe_customer_id, status
                        """)
                .param("w", workspaceId)
                .param("ids", customerIds.toArray(String[]::new))
                .query((rs, n) -> Map.entry(rs.getString("stripe_customer_id"), rs.getString("status")))
                .list()
                .forEach(pair -> byCustomer
                        .computeIfAbsent(pair.getKey(), key -> new java.util.ArrayList<>())
                        .add(pair.getValue()));
        return byCustomer;
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
            boolean deleted,
            OffsetDateTime providerCreatedAt,
            OffsetDateTime acquisitionAt,
            String confidence,
            String unattributedReason,
            String firstSource) {}

    /** Opaque, stable keyset cursor over {@code (provider_created_at, stripe_customer_id)} descending. */
    private record Cursor(OffsetDateTime createdAt, String customerId) {
        String encode() {
            String raw = createdAt.toInstant() + "|" + customerId;
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
                String id = raw.substring(separator + 1);
                return Optional.of(new Cursor(at, id));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("malformed cursor", malformed);
            }
        }
    }

    public record Page(List<Entry> entries, String nextCursor) {}

    /**
     * {@code confidence}/{@code unattributedReason}/{@code firstSource} are null when the customer has
     * no {@code NEW} movement yet, or attribution has not been (re)calculated for it under the current
     * model version -- an operational gap, never a fabricated negative result.
     */
    public record Entry(
            String stripeCustomerId,
            boolean deleted,
            OffsetDateTime providerCreatedAt,
            OffsetDateTime acquisitionEffectiveAt,
            String confidence,
            String unattributedReason,
            String firstSource,
            List<CurrentMrrByCurrency> currentMrr,
            List<String> subscriptionStatuses) {}

    public record CurrentMrrByCurrency(String currency, long amountMinor) {}
}
