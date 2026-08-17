package com.mrrorigin.reporting;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.reporting.CustomerDirectoryService.CurrentMrrByCurrency;
import com.mrrorigin.reporting.CustomerDirectoryService.Entry;
import com.mrrorigin.reporting.CustomerDirectoryService.Page;

/**
 * {@code customers-v1} CSV export (#26): the one export whose row count is not bounded by a small
 * dimension cardinality, so unlike the comparison/retention-cohorts exports this one genuinely
 * streams from the database -- it walks {@link CustomerDirectoryService}'s own keyset cursor page by
 * page (rather than fetching everything into one list first) and writes each page's rows before
 * fetching the next, keeping memory bounded regardless of customer count.
 *
 * <p><b>Row order</b>: {@code provider_created_at DESC, stripe_customer_id DESC} -- the same order
 * {@link CustomerDirectoryService}'s cursor already produces -- is the primary key, not {@code
 * currency}, precisely so this streaming property holds: sorting by currency first would require
 * buffering the entire customer list before writing a single row, which is the one thing a
 * potentially unbounded export must not do. {@code currency ASC NULLS LAST} is still honored as a
 * tiebreak among one customer's own (typically one or two) currency rows.
 *
 * <p>{@code external_user_id} is redacted (blank) unless the caller can manage the workspace,
 * reusing the exact rule {@code CustomerTimelineService} introduced in PR #57 -- not a parallel one.
 */
@Service
class CustomersCsvExportService {

    static final String SCHEMA_VERSION = "customers-v1";

    static final List<String> HEADER = List.of(
            "stripe_customer_id", "deleted", "provider_created_at", "acquisition_effective_at",
            "acquisition_confidence", "unattributed_reason", "first_source", "first_source_bucket",
            "currency", "current_mrr_amount_minor", "subscription_statuses", "external_user_id",
            "evidence_link");

    private final CustomerDirectoryService customerDirectoryService;
    private final JdbcClient db;

    CustomersCsvExportService(CustomerDirectoryService customerDirectoryService, JdbcClient db) {
        this.customerDirectoryService = customerDirectoryService;
        this.db = db;
    }

    long write(Writer out, UUID workspaceId, UUID projectId, boolean canViewSensitiveIdentity) throws IOException {
        CsvWriter.writeRow(out, HEADER);
        long count = 0;
        String cursor = null;
        do {
            Page page = customerDirectoryService.list(workspaceId, projectId, null, cursor, CustomerDirectoryService.MAX_LIMIT);
            Map<String, String> externalUserIds = canViewSensitiveIdentity
                    ? activeLinkExternalUserIds(workspaceId, projectId, page.entries())
                    : Map.of();
            for (Entry entry : page.entries()) {
                for (List<String> fields : rowsFor(workspaceId, projectId, entry, externalUserIds)) {
                    CsvWriter.writeRow(out, fields);
                    count++;
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return count;
    }

    /**
     * Active-link {@code external_user_id} per customer, redaction-gated by {@code
     * canViewSensitiveIdentity} exactly like {@code CustomerTimelineService}'s identical rule from PR
     * #57 -- a separate, page-scoped batch query rather than widening #24's already-shipped {@link
     * CustomerDirectoryService} contract for one column only this export needs.
     */
    private Map<String, String> activeLinkExternalUserIds(UUID workspaceId, UUID projectId, List<Entry> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        String[] customerIds = entries.stream().map(Entry::stripeCustomerId).toArray(String[]::new);
        Map<String, String> byCustomer = new java.util.HashMap<>();
        db.sql(
                        """
                        SELECT l.stripe_customer_id, i.external_user_id
                        FROM stripe_customer_links l
                        JOIN external_identities i
                          ON i.id = l.external_identity_id AND i.workspace_id = l.workspace_id AND i.project_id = l.project_id
                        WHERE l.workspace_id = :w AND l.project_id = :p AND l.stripe_customer_id = ANY(:ids)
                          AND l.superseded_at IS NULL
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("ids", customerIds)
                .query((rs, n) -> Map.entry(rs.getString("stripe_customer_id"), rs.getString("external_user_id")))
                .list()
                .forEach(pair -> byCustomer.put(pair.getKey(), pair.getValue()));
        return byCustomer;
    }

    private static List<List<String>> rowsFor(
            UUID workspaceId, UUID projectId, Entry entry, Map<String, String> externalUserIds) {
        String bucket = entry.confidence() == null
                ? null
                : ("STRONG".equals(entry.confidence()) ? (entry.firstSource() != null ? null : "NONE") : "UNATTRIBUTED");
        String subscriptionStatuses = entry.subscriptionStatuses().stream().sorted().reduce((a, b) -> a + ";" + b).orElse("");
        String externalUserId = externalUserIds.get(entry.stripeCustomerId());
        String evidenceLink = EvidenceLink.customerTimelinePath(workspaceId, projectId, entry.stripeCustomerId());

        List<CurrentMrrByCurrency> currencies = entry.currentMrr().stream()
                .sorted((a, b) -> a.currency().compareTo(b.currency()))
                .toList();
        if (currencies.isEmpty()) {
            return List.of(row(entry, bucket, null, null, subscriptionStatuses, externalUserId, evidenceLink));
        }
        List<List<String>> rows = new ArrayList<>(currencies.size());
        for (CurrentMrrByCurrency mrr : currencies) {
            rows.add(row(entry, bucket, mrr.currency(), mrr.amountMinor(), subscriptionStatuses, externalUserId, evidenceLink));
        }
        return rows;
    }

    private static List<String> row(
            Entry entry,
            String bucket,
            String currency,
            Long currentMrrAmountMinor,
            String subscriptionStatuses,
            String externalUserId,
            String evidenceLink) {
        List<String> fields = new ArrayList<>(HEADER.size());
        fields.add(entry.stripeCustomerId());
        fields.add(String.valueOf(entry.deleted()));
        fields.add(entry.providerCreatedAt() == null ? null : entry.providerCreatedAt().toString());
        fields.add(entry.acquisitionEffectiveAt() == null ? null : entry.acquisitionEffectiveAt().toString());
        fields.add(entry.confidence());
        fields.add(entry.unattributedReason());
        fields.add(entry.firstSource());
        fields.add(bucket);
        fields.add(currency);
        fields.add(currentMrrAmountMinor == null ? null : String.valueOf(currentMrrAmountMinor));
        fields.add(subscriptionStatuses);
        fields.add(externalUserId);
        fields.add(evidenceLink);
        return fields;
    }
}
