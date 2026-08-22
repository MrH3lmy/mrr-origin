package com.mrrorigin.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mrrorigin.billing.BillingMrrRecalculationPort.MrrDiscount;
import com.mrrorigin.billing.BillingMrrRecalculationPort.MrrItem;
import com.mrrorigin.billing.BillingMrrRecalculationPort.SubscriptionMrrSnapshot;
import com.mrrorigin.billing.BillingSourceVersion.SourceVersion;
import com.mrrorigin.billing.StripeBillingObjects.ParsedCustomer;
import com.mrrorigin.billing.StripeBillingObjects.ParsedDiscount;
import com.mrrorigin.billing.StripeBillingObjects.ParsedInvoice;
import com.mrrorigin.billing.StripeBillingObjects.ParsedPayment;
import com.mrrorigin.billing.StripeBillingObjects.ParsedPrice;
import com.mrrorigin.billing.StripeBillingObjects.ParsedRefund;
import com.mrrorigin.billing.StripeBillingObjects.ParsedSubscription;
import com.mrrorigin.billing.StripeBillingObjects.ParsedSubscriptionItem;

/**
 * Applies parsed Stripe objects to the normalized billing ledger (V7). This is the single
 * convergence point for both the resumable backfill and the webhook normalizer: every upsert here
 * is a plain, atomic, version-guarded SQL statement, so calling it twice with the same input (a
 * retried page, a redelivered webhook), or out of order (an older snapshot arriving after a newer
 * one), always leaves the ledger in the same state. Rows-affected is never distinguished between
 * "fresh insert", "applied update", and "no-op because a same-or-newer version already existed" --
 * all three are success from the caller's point of view.
 *
 * <p>Every guard compares the full {@code (source_version, source_sequence)} pair as a Postgres row
 * value, not {@code source_version} alone -- see {@link BillingSourceVersion}.
 */
@Service
@Transactional
class BillingLedgerUpsertService {

    /** Namespaces this service's Postgres advisory locks; distinct from StripeConnectionService's. */
    private static final int SUBSCRIPTION_LOCK_NAMESPACE = 914_101;

    private final JdbcClient jdbc;
    private final BillingMrrRecalculationPort mrrRecalculation;

    BillingLedgerUpsertService(JdbcClient jdbc, BillingMrrRecalculationPort mrrRecalculation) {
        this.jdbc = jdbc;
        this.mrrRecalculation = mrrRecalculation;
    }

    void upsertCustomer(UUID workspaceId, ParsedCustomer customer, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, currency, deleted, provider_created_at,
                             source, source_version, source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeCustomerId, :currency, :deleted, :providerCreatedAt,
                             :source, :sourceVersion, :sourceSequence, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_customer_id) DO UPDATE SET
                            currency = EXCLUDED.currency,
                            deleted = EXCLUDED.deleted,
                            provider_created_at = EXCLUDED.provider_created_at,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_customers.source_version, billing_customers.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", customer.stripeCustomerId())
                .param("currency", customer.currency())
                .param("deleted", customer.deleted())
                .param("providerCreatedAt", customer.providerCreatedAt())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .update();

        customer.discount().ifPresent(discount -> upsertDiscount(workspaceId, discount, sourceVersion, source));
    }

    void upsertPrice(UUID workspaceId, ParsedPrice price, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_prices
                            (id, workspace_id, stripe_price_id, stripe_product_id, currency, unit_amount,
                             billing_scheme, type, recurring_interval, recurring_interval_count, usage_type, active,
                             source, source_version, source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripePriceId, :stripeProductId, :currency, :unitAmount,
                             :billingScheme, :type, :recurringInterval, :recurringIntervalCount, :usageType, :active,
                             :source, :sourceVersion, :sourceSequence, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_price_id) DO UPDATE SET
                            stripe_product_id = EXCLUDED.stripe_product_id,
                            currency = EXCLUDED.currency,
                            unit_amount = EXCLUDED.unit_amount,
                            billing_scheme = EXCLUDED.billing_scheme,
                            type = EXCLUDED.type,
                            recurring_interval = EXCLUDED.recurring_interval,
                            recurring_interval_count = EXCLUDED.recurring_interval_count,
                            usage_type = EXCLUDED.usage_type,
                            active = EXCLUDED.active,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_prices.source_version, billing_prices.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripePriceId", price.stripePriceId())
                .param("stripeProductId", price.stripeProductId())
                .param("currency", price.currency())
                .param("unitAmount", price.unitAmount())
                .param("billingScheme", price.billingScheme())
                .param("type", price.type())
                .param("recurringInterval", price.recurringInterval())
                .param("recurringIntervalCount", price.recurringIntervalCount())
                .param("usageType", price.usageType())
                .param("active", price.active())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .update();
    }

    /**
     * Locks this subscription (a Postgres advisory lock, held for the rest of the transaction) so
     * concurrent upserts for the same subscription fully serialize, including the very first
     * creation race -- not just the row-level lock {@code INSERT ... ON CONFLICT} already takes
     * once a row exists. This is what makes {@code previousStatus} below always accurate: no other
     * transaction can be mid-upsert of this same subscription between the plain SELECT that reads
     * it and the INSERT that changes it.
     *
     * <p>A plain sibling-CTE {@code SELECT ... FOR UPDATE} was tried first and rejected: Postgres
     * evaluates a read-only CTE's own snapshot independently of a data-modifying sibling CTE in the
     * same statement, and combining {@code FOR UPDATE} there returned stale/empty results even
     * outside of any concurrency (verified against a real Postgres instance). Two separate
     * statements under an advisory lock avoids that entirely.
     */
    private void lockSubscriptionForUpsert(UUID workspaceId, String stripeSubscriptionId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:namespace, hashtext(:key))) AS lock_acquired")
                .param("namespace", SUBSCRIPTION_LOCK_NAMESPACE)
                .param("key", workspaceId + ":" + stripeSubscriptionId)
                .query(Integer.class)
                .single();
    }

    void upsertSubscription(
            UUID workspaceId, ParsedSubscription subscription, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        lockSubscriptionForUpsert(workspaceId, subscription.stripeSubscriptionId());

        String previousStatus = jdbc.sql(
                        "SELECT status FROM billing_subscriptions WHERE workspace_id = :workspaceId AND stripe_subscription_id = :stripeSubscriptionId")
                .param("workspaceId", workspaceId)
                .param("stripeSubscriptionId", subscription.stripeSubscriptionId())
                .query(String.class)
                .optional()
                .orElse(null);

        Optional<SubscriptionUpsertOutcome> outcome = jdbc.sql(
                        """
                        INSERT INTO billing_subscriptions
                            (id, workspace_id, stripe_subscription_id, stripe_customer_id, status, currency,
                             current_period_start, current_period_end, cancel_at_period_end, cancel_at,
                             canceled_at, ended_at, trial_start, trial_end, collection_method, source,
                             source_version, source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeSubscriptionId, :stripeCustomerId, :status, :currency,
                             :currentPeriodStart, :currentPeriodEnd, :cancelAtPeriodEnd, :cancelAt,
                             :canceledAt, :endedAt, :trialStart, :trialEnd, :collectionMethod, :source,
                             :sourceVersion, :sourceSequence, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_subscription_id) DO UPDATE SET
                            stripe_customer_id = EXCLUDED.stripe_customer_id,
                            status = EXCLUDED.status,
                            currency = EXCLUDED.currency,
                            current_period_start = EXCLUDED.current_period_start,
                            current_period_end = EXCLUDED.current_period_end,
                            cancel_at_period_end = EXCLUDED.cancel_at_period_end,
                            cancel_at = EXCLUDED.cancel_at,
                            canceled_at = EXCLUDED.canceled_at,
                            ended_at = EXCLUDED.ended_at,
                            trial_start = EXCLUDED.trial_start,
                            trial_end = EXCLUDED.trial_end,
                            collection_method = EXCLUDED.collection_method,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_subscriptions.source_version, billing_subscriptions.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        RETURNING id, status
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeSubscriptionId", subscription.stripeSubscriptionId())
                .param("stripeCustomerId", subscription.stripeCustomerId())
                .param("status", subscription.status())
                .param("currency", subscription.currency())
                .param("currentPeriodStart", subscription.currentPeriodStart())
                .param("currentPeriodEnd", subscription.currentPeriodEnd())
                .param("cancelAtPeriodEnd", subscription.cancelAtPeriodEnd())
                .param("cancelAt", subscription.cancelAt())
                .param("canceledAt", subscription.canceledAt())
                .param("endedAt", subscription.endedAt())
                .param("trialStart", subscription.trialStart())
                .param("trialEnd", subscription.trialEnd())
                .param("collectionMethod", subscription.collectionMethod())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .query((rs, rowNum) -> new SubscriptionUpsertOutcome(UUID.fromString(rs.getString("id")), rs.getString("status")))
                .optional();

        if (outcome.isEmpty()) {
            // Stale relative to what's already stored: skip items, discounts, and status-transition
            // bookkeeping too -- none of this event's data is any newer than what's already applied.
            return;
        }
        SubscriptionUpsertOutcome applied = outcome.get();

        if (previousStatus == null || !previousStatus.equals(applied.newStatus())) {
            jdbc.sql(
                            """
                            INSERT INTO billing_subscription_status_events
                                (id, workspace_id, subscription_id, stripe_subscription_id, previous_status,
                                 new_status, source, source_version, source_sequence)
                            VALUES
                                (:id, :workspaceId, :subscriptionId, :stripeSubscriptionId, :previousStatus,
                                 :newStatus, :source, :sourceVersion, :sourceSequence)
                            ON CONFLICT (workspace_id, stripe_subscription_id, source_version, source_sequence) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID())
                    .param("workspaceId", workspaceId)
                    .param("subscriptionId", applied.id())
                    .param("stripeSubscriptionId", subscription.stripeSubscriptionId())
                    .param("previousStatus", previousStatus)
                    .param("newStatus", applied.newStatus())
                    .param("source", source.name())
                    .param("sourceVersion", sourceVersion.version())
                    .param("sourceSequence", sourceVersion.sequence())
                    .update();
        }

        replaceSubscriptionItems(workspaceId, applied.id(), subscription.items(), sourceVersion);
        for (ParsedDiscount discount : subscription.discounts()) {
            upsertDiscount(workspaceId, discount, sourceVersion, source);
        }
        for (ParsedSubscriptionItem item : subscription.items()) {
            for (ParsedDiscount discount : item.discounts()) {
                upsertDiscount(workspaceId, discount, sourceVersion, source);
            }
        }

        recalculateMrr(workspaceId, subscription, previousStatus, sourceVersion, source);
    }

    /**
     * Triggers deterministic MRR recalculation for the subscription this upsert just applied, per
     * ADR-0010: once per accepted (non-stale) normalization, in the same transaction as the ledger
     * write above, using this event's own parsed item/discount list (a Stripe subscription payload
     * always carries the complete current item list, never a partial diff).
     */
    private void recalculateMrr(
            UUID workspaceId,
            ParsedSubscription subscription,
            String previousStatus,
            SourceVersion sourceVersion,
            BillingLedgerSource source) {
        OffsetDateTime providerAt = Instant.ofEpochSecond(sourceVersion.version()).atOffset(ZoneOffset.UTC);
        OffsetDateTime effectiveAt = SubscriptionMrrEffectiveAt.resolve(previousStatus, subscription, providerAt);
        // Both components of the ordering pair are required for a globally unique reference: a
        // backfill fetch's `sequence` alone is only a nanosecond-within-second fraction
        // (BillingSourceVersion.forBackfillFetch), so two fetches in different seconds can share the
        // same nanosecond fraction and collide without `version` disambiguating them too.
        String sourceBillingReference = source.name() + ":" + sourceVersion.version() + ":" + sourceVersion.sequence()
                + ":" + subscription.stripeSubscriptionId();

        List<MrrItem> items = toMrrItems(workspaceId, subscription.items());

        List<MrrDiscount> discounts = new ArrayList<>();
        for (ParsedDiscount discount : subscription.discounts()) {
            discounts.add(toMrrDiscount(discount));
        }
        for (ParsedSubscriptionItem item : subscription.items()) {
            for (ParsedDiscount discount : item.discounts()) {
                discounts.add(toMrrDiscount(discount));
            }
        }
        for (MrrDiscount customerDiscount : activeCustomerDiscounts(
                workspaceId, subscription.stripeCustomerId(), effectiveAt)) {
            // Prefer the ledger row when Stripe also expands the equivalent customer discount in
            // the subscription payload: it retains customer scope for fixed-allocation validation.
            discounts.removeIf(existing -> equivalentDiscount(existing, customerDiscount));
            discounts.add(customerDiscount);
        }

        mrrRecalculation.recalculateSubscription(new SubscriptionMrrSnapshot(
                workspaceId,
                subscription.stripeCustomerId(),
                subscription.stripeSubscriptionId(),
                effectiveAt,
                subscription.status(),
                sourceBillingReference,
                items,
                discounts));
    }

    /** Shared by a fresh subscription payload's own items and {@link #currentMrrItems}'s ledger-reconstructed ones. */
    private List<MrrItem> toMrrItems(UUID workspaceId, List<ParsedSubscriptionItem> items) {
        Map<String, ParsedPrice> pricesById = resolvePrices(workspaceId, items);
        List<MrrItem> result = new ArrayList<>();
        for (ParsedSubscriptionItem item : items) {
            ParsedPrice price = pricesById.get(item.stripePriceId());
            result.add(new MrrItem(
                    item.stripeSubscriptionItemId(),
                    price == null ? null : upperCase(price.currency()),
                    price == null ? null : price.unitAmount(),
                    BigDecimal.valueOf(item.quantity()),
                    price == null ? null : price.recurringInterval(),
                    price == null ? null : price.recurringIntervalCount(),
                    isUsagePricing(price)));
        }
        return result;
    }

    /**
     * A metered recurring price can still carry a non-null {@code unit_amount} (the per-unit rate),
     * so {@code unit_amount} presence alone cannot distinguish it from a fixed recurring charge; a
     * tiered price has no single {@code unit_amount} representing the whole period either. Both must
     * fail visibly as {@code UNSUPPORTED_USAGE_PRICING}/{@code UNSUPPORTED_INTERVAL} per ADR-0004
     * rather than being calculated as ordinary MRR.
     */
    private static boolean isUsagePricing(ParsedPrice price) {
        return price != null && ("metered".equals(price.usageType()) || "tiered".equals(price.billingScheme()));
    }

    private static MrrDiscount toMrrDiscount(ParsedDiscount discount) {
        return new MrrDiscount(
                discount.stripeDiscountId(),
                discount.stripeSubscriptionItemId(),
                discount.percentOff(),
                discount.amountOff(),
                upperCase(discount.currency()),
                discount.startAt(),
                discount.endAt(),
                false);
    }

    /**
     * A customer's discounts whose effective window covers {@code effectiveAt}, as best-known from
     * {@code billing_discounts}' current (upserted, not historical) state. Deliberately does NOT
     * filter on {@code deleted}: per ADR-0011's historical-state amendment, a deleted discount must
     * still be returned for an older {@code effectiveAt} that falls before its actual deletion/end
     * instant -- {@link #upsertDiscountReportingApplied} guarantees {@code end_at} always reflects
     * that instant once deleted (Stripe's own {@code end}, or the same provider-event-second
     * fallback ADR-0010 already established), so the temporal window alone is authoritative and
     * {@code deleted} would only ever narrow it incorrectly.
     *
     * <p>The lower bound is {@code first_seen_start_at} (this discount identity's earliest-ever
     * start_at, frozen at first insert), not the current {@code start_at}: the latter moves forward
     * on a genuine {@code customer.discount.updated} coupon switch (Stripe: "occurs whenever a
     * customer is switched from one coupon to another"), and this table keeps only the current
     * coupon's terms -- the prior coupon's are gone. A row matched only by the broader bound (i.e.
     * {@code effectiveAt} predates the current {@code start_at}) is a provable case of "the terms
     * that actually applied at effectiveAt are not the ones stored here, and cannot be reconstructed
     * from normalized state" -- ADR-0004/#86 requires refusing to guess rather than emitting
     * plausible-but-wrong MRR, so that case throws instead of silently returning wrong terms or
     * silently omitting the discount (either of which would be exactly the stale-MRR bug this
     * amendment closes).
     */
    private List<MrrDiscount> activeCustomerDiscounts(
            UUID workspaceId, String stripeCustomerId, OffsetDateTime effectiveAt) {
        return jdbc.sql(
                        """
                        SELECT stripe_discount_id, percent_off, amount_off, currency, start_at, end_at
                        FROM billing_discounts
                        WHERE workspace_id = :workspaceId
                          AND stripe_customer_id = :stripeCustomerId
                          AND stripe_subscription_id IS NULL
                          AND stripe_subscription_item_id IS NULL
                          AND first_seen_start_at <= :effectiveAt
                          AND (end_at IS NULL OR end_at > :effectiveAt)
                        ORDER BY stripe_discount_id
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("effectiveAt", effectiveAt)
                .query((rs, rowNum) -> {
                    String stripeDiscountId = rs.getString("stripe_discount_id");
                    OffsetDateTime startAt = rs.getObject("start_at", OffsetDateTime.class);
                    if (effectiveAt.isBefore(startAt)) {
                        throw new StripeBillingNormalizationException(
                                "Customer-level discount " + stripeDiscountId + " for customer " + stripeCustomerId
                                        + " was updated (coupon switch) since " + effectiveAt + " -- its terms as of"
                                        + " that instant are not recoverable from normalized billing_discounts"
                                        + " state (current terms only effective from " + startAt + ").");
                    }
                    return new MrrDiscount(
                            stripeDiscountId, null, rs.getBigDecimal("percent_off"),
                            (Long) rs.getObject("amount_off"), upperCase(rs.getString("currency")),
                            startAt, rs.getObject("end_at", OffsetDateTime.class), true);
                })
                .list();
    }

    private static boolean equivalentDiscount(MrrDiscount left, MrrDiscount right) {
        return left.sourceReference().equals(right.sourceReference())
                || (left.itemReference() == null && right.itemReference() == null
                        && sameDecimal(left.percentOff(), right.percentOff())
                        && java.util.Objects.equals(left.amountOffMinor(), right.amountOffMinor())
                        && java.util.Objects.equals(left.currency(), right.currency())
                        && java.util.Objects.equals(left.startAt(), right.startAt())
                        && java.util.Objects.equals(left.endAt(), right.endAt()));
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    /**
     * Stripe's own API always returns lowercase ISO currency codes; {@code RevenueCalculationService}
     * requires uppercase per ADR-0004. This is a formatting normalization, not currency inference --
     * the billing ledger itself (e.g. {@code billing_prices.currency}) keeps Stripe's original case.
     */
    private static String upperCase(String currency) {
        return currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT);
    }

    /** Resolves each referenced item's price economics from the ledger's own price table. */
    private Map<String, ParsedPrice> resolvePrices(UUID workspaceId, List<ParsedSubscriptionItem> items) {
        List<String> priceIds = items.stream().map(ParsedSubscriptionItem::stripePriceId).distinct().toList();
        if (priceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ParsedPrice> byId = new HashMap<>();
        jdbc.sql(
                        """
                        SELECT stripe_price_id, stripe_product_id, currency, unit_amount, billing_scheme, type,
                               recurring_interval, recurring_interval_count, usage_type, active
                        FROM billing_prices WHERE workspace_id = :workspaceId AND stripe_price_id IN (:priceIds)
                        """)
                .param("workspaceId", workspaceId)
                .param("priceIds", priceIds)
                .query((rs, rowNum) -> new ParsedPrice(
                        rs.getString("stripe_price_id"),
                        rs.getString("stripe_product_id"),
                        rs.getString("currency"),
                        (Long) rs.getObject("unit_amount"),
                        rs.getString("billing_scheme"),
                        rs.getString("type"),
                        rs.getString("recurring_interval"),
                        (Integer) rs.getObject("recurring_interval_count"),
                        rs.getString("usage_type"),
                        rs.getBoolean("active")))
                .list()
                .forEach(price -> byId.put(price.stripePriceId(), price));
        return byId;
    }

    /**
     * The customer's subscriptions currently in an MRR-retaining status (ADR-0004: only {@code
     * active}/{@code past_due} do). Used by {@link CustomerDiscountMrrRecalculationService} to find
     * which subscriptions a legacy customer-level discount change could actually affect -- a churned
     * or trialing subscription contributes zero MRR regardless of discount state, so recalculating it
     * would be a needless no-op, and counting it toward "how many subscriptions does this ambiguous
     * fixed discount span" would over-flag ambiguity that was never real.
     */
    List<AffectedSubscription> activeSubscriptionsForCustomer(UUID workspaceId, String stripeCustomerId) {
        return jdbc.sql(
                        """
                        SELECT id, stripe_subscription_id, status FROM billing_subscriptions
                        WHERE workspace_id = :workspaceId AND stripe_customer_id = :stripeCustomerId
                          AND status IN ('active', 'past_due')
                        ORDER BY stripe_subscription_id
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .query((rs, rowNum) -> new AffectedSubscription(
                        UUID.fromString(rs.getString("id")), rs.getString("stripe_subscription_id"), rs.getString("status")))
                .list();
    }

    /**
     * A subscription's current items, reconstructed from the ledger's own authoritative "current
     * state" tables ({@code billing_subscription_items} is fully replaced on every accepted
     * subscription upsert -- see {@link #replaceSubscriptionItems} -- so it carries no more staleness
     * risk than what {@link #recalculateMrr} already trusts for a freshly-parsed payload).
     */
    List<MrrItem> currentMrrItems(UUID workspaceId, UUID subscriptionId) {
        List<ParsedSubscriptionItem> items = jdbc.sql(
                        """
                        SELECT stripe_subscription_item_id, stripe_price_id, quantity
                        FROM billing_subscription_items
                        WHERE workspace_id = :workspaceId AND subscription_id = :subscriptionId
                        ORDER BY stripe_subscription_item_id
                        """)
                .param("workspaceId", workspaceId)
                .param("subscriptionId", subscriptionId)
                .query((rs, rowNum) -> new ParsedSubscriptionItem(
                        rs.getString("stripe_subscription_item_id"), rs.getString("stripe_price_id"), rs.getInt("quantity"), List.of()))
                .list();
        return toMrrItems(workspaceId, items);
    }

    /**
     * A subscription's currently active (non-deleted) subscription-level and item-level discounts, as
     * already normalized into {@code billing_discounts} by prior {@code customer.subscription.*}
     * events. Used to detect pre-existing discount stacking when a legacy customer-level discount is
     * layered on top: {@code RevenueCalculationService}'s own {@code discounts.size() > 1} guard then
     * fails the combination visibly (ADR-0004: unsupported discount stacking is never approximated)
     * without this class needing any new stacking-detection logic of its own.
     */
    List<MrrDiscount> currentDiscounts(UUID workspaceId, String stripeSubscriptionId, List<String> stripeItemIds) {
        Map<String, MrrDiscount> byDiscountId = new java.util.LinkedHashMap<>();
        for (MrrDiscount discount : jdbc.sql(
                        """
                        SELECT stripe_discount_id, stripe_subscription_item_id, percent_off, amount_off, currency,
                               start_at, end_at
                        FROM billing_discounts
                        WHERE workspace_id = :workspaceId AND stripe_subscription_id = :stripeSubscriptionId
                          AND deleted = false
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeSubscriptionId", stripeSubscriptionId)
                .query(BillingLedgerUpsertService::mapDiscountRow)
                .list()) {
            byDiscountId.put(discount.sourceReference(), discount);
        }
        if (!stripeItemIds.isEmpty()) {
            for (MrrDiscount discount : jdbc.sql(
                            """
                            SELECT stripe_discount_id, stripe_subscription_item_id, percent_off, amount_off, currency,
                                   start_at, end_at
                            FROM billing_discounts
                            WHERE workspace_id = :workspaceId AND stripe_subscription_item_id IN (:stripeItemIds)
                              AND deleted = false
                            """)
                    .param("workspaceId", workspaceId)
                    .param("stripeItemIds", stripeItemIds)
                    .query(BillingLedgerUpsertService::mapDiscountRow)
                    .list()) {
                byDiscountId.put(discount.sourceReference(), discount);
            }
        }
        return List.copyOf(byDiscountId.values());
    }

    private static MrrDiscount mapDiscountRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MrrDiscount(
                rs.getString("stripe_discount_id"),
                rs.getString("stripe_subscription_item_id"),
                rs.getBigDecimal("percent_off"),
                (Long) rs.getObject("amount_off"),
                upperCase(rs.getString("currency")),
                rs.getObject("start_at", OffsetDateTime.class),
                rs.getObject("end_at", OffsetDateTime.class),
                false);
    }

    /** One of a customer's subscriptions currently in an MRR-retaining status. See {@link #activeSubscriptionsForCustomer}. */
    record AffectedSubscription(UUID id, String stripeSubscriptionId, String status) {}

    private void replaceSubscriptionItems(
            UUID workspaceId, UUID subscriptionId, java.util.List<ParsedSubscriptionItem> items, SourceVersion sourceVersion) {
        jdbc.sql("DELETE FROM billing_subscription_items WHERE subscription_id = :subscriptionId")
                .param("subscriptionId", subscriptionId)
                .update();
        OffsetDateTime now = now();
        for (ParsedSubscriptionItem item : items) {
            jdbc.sql(
                            """
                            INSERT INTO billing_subscription_items
                                (id, workspace_id, subscription_id, stripe_subscription_item_id, stripe_price_id,
                                 quantity, source_version, source_sequence, updated_at)
                            VALUES
                                (:id, :workspaceId, :subscriptionId, :stripeItemId, :stripePriceId, :quantity,
                                 :sourceVersion, :sourceSequence, :updatedAt)
                            ON CONFLICT (workspace_id, stripe_subscription_item_id) DO UPDATE SET
                                subscription_id = EXCLUDED.subscription_id,
                                stripe_price_id = EXCLUDED.stripe_price_id,
                                quantity = EXCLUDED.quantity,
                                source_version = EXCLUDED.source_version,
                                source_sequence = EXCLUDED.source_sequence,
                                updated_at = EXCLUDED.updated_at
                            WHERE (billing_subscription_items.source_version, billing_subscription_items.source_sequence)
                                  <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("workspaceId", workspaceId)
                    .param("subscriptionId", subscriptionId)
                    .param("stripeItemId", item.stripeSubscriptionItemId())
                    .param("stripePriceId", item.stripePriceId())
                    .param("quantity", item.quantity())
                    .param("sourceVersion", sourceVersion.version())
                    .param("sourceSequence", sourceVersion.sequence())
                    .param("updatedAt", now)
                    .update();
        }
    }

    void upsertInvoice(UUID workspaceId, ParsedInvoice invoice, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_invoices
                            (id, workspace_id, stripe_invoice_id, stripe_customer_id, stripe_subscription_id,
                             status, currency, amount_due, amount_paid, amount_remaining, period_start,
                             period_end, provider_created_at, source, source_version, source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeInvoiceId, :stripeCustomerId, :stripeSubscriptionId,
                             :status, :currency, :amountDue, :amountPaid, :amountRemaining, :periodStart,
                             :periodEnd, :providerCreatedAt, :source, :sourceVersion, :sourceSequence, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_invoice_id) DO UPDATE SET
                            stripe_customer_id = EXCLUDED.stripe_customer_id,
                            stripe_subscription_id = EXCLUDED.stripe_subscription_id,
                            status = EXCLUDED.status,
                            currency = EXCLUDED.currency,
                            amount_due = EXCLUDED.amount_due,
                            amount_paid = EXCLUDED.amount_paid,
                            amount_remaining = EXCLUDED.amount_remaining,
                            period_start = EXCLUDED.period_start,
                            period_end = EXCLUDED.period_end,
                            provider_created_at = EXCLUDED.provider_created_at,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_invoices.source_version, billing_invoices.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeInvoiceId", invoice.stripeInvoiceId())
                .param("stripeCustomerId", invoice.stripeCustomerId())
                .param("stripeSubscriptionId", invoice.stripeSubscriptionId())
                .param("status", invoice.status())
                .param("currency", invoice.currency())
                .param("amountDue", invoice.amountDue())
                .param("amountPaid", invoice.amountPaid())
                .param("amountRemaining", invoice.amountRemaining())
                .param("periodStart", invoice.periodStart())
                .param("periodEnd", invoice.periodEnd())
                .param("providerCreatedAt", invoice.providerCreatedAt())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .update();
    }

    void upsertPayment(UUID workspaceId, ParsedPayment payment, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_payments
                            (id, workspace_id, stripe_charge_id, stripe_customer_id, stripe_invoice_id, amount,
                             currency, status, paid, refunded, amount_refunded, provider_created_at, source,
                             source_version, source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeChargeId, :stripeCustomerId, :stripeInvoiceId, :amount,
                             :currency, :status, :paid, :refunded, :amountRefunded, :providerCreatedAt, :source,
                             :sourceVersion, :sourceSequence, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_charge_id) DO UPDATE SET
                            stripe_customer_id = EXCLUDED.stripe_customer_id,
                            stripe_invoice_id = EXCLUDED.stripe_invoice_id,
                            amount = EXCLUDED.amount,
                            currency = EXCLUDED.currency,
                            status = EXCLUDED.status,
                            paid = EXCLUDED.paid,
                            refunded = EXCLUDED.refunded,
                            amount_refunded = EXCLUDED.amount_refunded,
                            provider_created_at = EXCLUDED.provider_created_at,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_payments.source_version, billing_payments.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeChargeId", payment.stripeChargeId())
                .param("stripeCustomerId", payment.stripeCustomerId())
                .param("stripeInvoiceId", payment.stripeInvoiceId())
                .param("amount", payment.amount())
                .param("currency", payment.currency())
                .param("status", payment.status())
                .param("paid", payment.paid())
                .param("refunded", payment.refunded())
                .param("amountRefunded", payment.amountRefunded())
                .param("providerCreatedAt", payment.providerCreatedAt())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .update();
    }

    void upsertRefund(UUID workspaceId, ParsedRefund refund, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_refunds
                            (id, workspace_id, stripe_refund_id, stripe_charge_id, amount, currency, status,
                             reason, provider_created_at, source, source_version, source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeRefundId, :stripeChargeId, :amount, :currency, :status,
                             :reason, :providerCreatedAt, :source, :sourceVersion, :sourceSequence, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_refund_id) DO UPDATE SET
                            stripe_charge_id = EXCLUDED.stripe_charge_id,
                            amount = EXCLUDED.amount,
                            currency = EXCLUDED.currency,
                            status = EXCLUDED.status,
                            reason = EXCLUDED.reason,
                            provider_created_at = EXCLUDED.provider_created_at,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_refunds.source_version, billing_refunds.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeRefundId", refund.stripeRefundId())
                .param("stripeChargeId", refund.stripeChargeId())
                .param("amount", refund.amount())
                .param("currency", refund.currency())
                .param("status", refund.status())
                .param("reason", refund.reason())
                .param("providerCreatedAt", refund.providerCreatedAt())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .update();
    }

    void upsertDiscount(UUID workspaceId, ParsedDiscount discount, SourceVersion sourceVersion, BillingLedgerSource source) {
        upsertDiscountReportingApplied(workspaceId, discount, sourceVersion, source);
    }

    /**
     * As {@link #upsertDiscount}, but reports two independent facts about the write, per ADR-0011's
     * retroactive-invalidation amendment -- {@link CustomerDiscountMrrRecalculationService} must be
     * able to tell them apart, because they call for different reactions:
     *
     * <ol>
     *   <li>{@link DiscountUpsertOutcome#currentStateApplied}: did this write win the version race and
     *       become the row's current coupon terms (a fresh insert, or an update whose version guard
     *       passed)? Mirrors {@link #upsertSubscription}'s own {@code Optional<SubscriptionUpsertOutcome>}
     *       pattern. {@code false} means the write was stale/out-of-order and must never trigger MRR
     *       recalculation using its own (regressed) terms (ADR-0004/#86).
     *   <li>{@link DiscountUpsertOutcome#historicalEvidenceChanged}: did this write's own {@code start}
     *       prove the discount identity existed earlier than previously known, regardless of whether it
     *       won the version race? {@code true} means some already-materialized MRR state may now be
     *       provably unsafe and must be invalidated -- {@code historicalEvidenceChanged} can be {@code
     *       true} even when {@code currentStateApplied} is {@code false}: a stale write's coupon terms
     *       are correctly rejected, but its {@code start} is still proof of existence that must not be
     *       discarded alongside them.
     * </ol>
     *
     * <p>Two independent statements below implement this split: the version-guarded upsert governs
     * <em>current state</em> only (coupon terms, {@code start_at}/{@code end_at}, {@code deleted}) --
     * unchanged, and still the sole input to {@code currentStateApplied}. {@link
     * #recordEarliestKnownDiscountStart} governs <em>historical evidence</em> and always runs, even when
     * the first statement was rejected as stale.
     */
    DiscountUpsertOutcome upsertDiscountReportingApplied(
            UUID workspaceId, ParsedDiscount discount, SourceVersion sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        // Per ADR-0011's historical-state amendment: a delete with no Stripe-provided `end` must
        // still persist SOME termination instant, or end_at stays NULL forever and every future
        // temporal query (current or historical) would treat this discount as open-ended --
        // reopening exactly the staleness gap #86 closed. Reuses the identical last-resort fallback
        // (the event's own provider second) CustomerDiscountMrrRecalculationService.effectiveAt
        // already computes for this same transition, rather than inventing a second rule.
        OffsetDateTime endAt = discount.deleted() && discount.endAt() == null
                ? Instant.ofEpochSecond(sourceVersion.version()).atOffset(ZoneOffset.UTC)
                : discount.endAt();
        boolean applied = jdbc.sql(
                        """
                        INSERT INTO billing_discounts
                            (id, workspace_id, stripe_discount_id, stripe_customer_id, stripe_subscription_id,
                             stripe_subscription_item_id, stripe_coupon_id, percent_off, amount_off, currency,
                             start_at, end_at, deleted, first_seen_start_at, source, source_version,
                             source_sequence, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeDiscountId, :stripeCustomerId, :stripeSubscriptionId,
                             :stripeSubscriptionItemId, :stripeCouponId, :percentOff, :amountOff, :currency,
                             :startAt, :endAt, :deleted, :startAt, :source, :sourceVersion, :sourceSequence,
                             :updatedAt)
                        ON CONFLICT (workspace_id, stripe_discount_id) DO UPDATE SET
                            stripe_customer_id = EXCLUDED.stripe_customer_id,
                            stripe_subscription_id = EXCLUDED.stripe_subscription_id,
                            stripe_subscription_item_id = EXCLUDED.stripe_subscription_item_id,
                            stripe_coupon_id = EXCLUDED.stripe_coupon_id,
                            percent_off = EXCLUDED.percent_off,
                            amount_off = EXCLUDED.amount_off,
                            currency = EXCLUDED.currency,
                            start_at = EXCLUDED.start_at,
                            end_at = EXCLUDED.end_at,
                            deleted = EXCLUDED.deleted,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            source_sequence = EXCLUDED.source_sequence,
                            updated_at = EXCLUDED.updated_at
                        WHERE (billing_discounts.source_version, billing_discounts.source_sequence)
                              <= (EXCLUDED.source_version, EXCLUDED.source_sequence)
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeDiscountId", discount.stripeDiscountId())
                .param("stripeCustomerId", discount.stripeCustomerId())
                .param("stripeSubscriptionId", discount.stripeSubscriptionId())
                .param("stripeSubscriptionItemId", discount.stripeSubscriptionItemId())
                .param("stripeCouponId", discount.stripeCouponId())
                .param("percentOff", discount.percentOff())
                .param("amountOff", discount.amountOff())
                .param("currency", discount.currency())
                .param("startAt", discount.startAt())
                .param("endAt", endAt)
                .param("deleted", discount.deleted())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion.version())
                .param("sourceSequence", sourceVersion.sequence())
                .param("updatedAt", now)
                .query(UUID.class)
                .optional()
                .isPresent();
        // Runs even when the write above was rejected as stale -- see the method Javadoc above.
        EvidenceWiden widen = recordEarliestKnownDiscountStart(workspaceId, discount.stripeDiscountId(), discount.startAt());
        return new DiscountUpsertOutcome(applied, widen.changed(), widen.from(), widen.to());
    }

    /**
     * Widens {@code first_seen_start_at} down to {@code candidateStartAt} if it is earlier than what
     * is already recorded, independent of the version guard that protects current coupon terms. Never
     * raises it, never touches any other column, and is a no-op when the candidate isn't earlier --
     * so calling it once per accepted-or-rejected discount write (including replays and duplicates)
     * converges to the same minimum regardless of delivery order or repetition. This is existence
     * evidence only: it lets {@code activeCustomerDiscounts} discover that a discount identity is
     * relevant to an older {@code effective_at} it would otherwise silently miss, not a claim about
     * what that identity's terms were at {@code candidateStartAt} -- {@code activeCustomerDiscounts}'s
     * own ambiguity check still refuses to guess those from the (possibly newer) currently-stored
     * terms.
     *
     * <p>Returns the pre- and post-update values so the caller can tell whether this write genuinely
     * widened the window (versus a no-op replay/duplicate) -- {@link CustomerDiscountMrrRecalculationService}
     * uses that to decide whether any already-materialized MRR state needs retroactive invalidation.
     * A single {@code UPDATE ... FROM} statement captures the pre-update value via the subquery (which
     * sees the pre-update snapshot) in the same round trip as the update itself.
     */
    private EvidenceWiden recordEarliestKnownDiscountStart(UUID workspaceId, String stripeDiscountId, OffsetDateTime candidateStartAt) {
        return jdbc.sql(
                        """
                        UPDATE billing_discounts b
                        SET first_seen_start_at = LEAST(b.first_seen_start_at, :candidateStartAt)
                        FROM (
                            SELECT first_seen_start_at FROM billing_discounts
                            WHERE workspace_id = :workspaceId AND stripe_discount_id = :stripeDiscountId
                        ) AS prev
                        WHERE b.workspace_id = :workspaceId AND b.stripe_discount_id = :stripeDiscountId
                        RETURNING prev.first_seen_start_at AS previous_value, b.first_seen_start_at AS new_value
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeDiscountId", stripeDiscountId)
                .param("candidateStartAt", candidateStartAt)
                .query((rs, rowNum) -> {
                    OffsetDateTime previous = rs.getObject("previous_value", OffsetDateTime.class);
                    OffsetDateTime updated = rs.getObject("new_value", OffsetDateTime.class);
                    return new EvidenceWiden(previous, updated, updated.isBefore(previous));
                })
                .single();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record SubscriptionUpsertOutcome(UUID id, String newStatus) {}

    /** Pre- and post-{@link #recordEarliestKnownDiscountStart} values for one discount write. */
    private record EvidenceWiden(OffsetDateTime from, OffsetDateTime to, boolean changed) {}

    /** See {@link #upsertDiscountReportingApplied}. */
    record DiscountUpsertOutcome(
            boolean currentStateApplied, boolean historicalEvidenceChanged, OffsetDateTime widenedFrom, OffsetDateTime widenedTo) {}
}
