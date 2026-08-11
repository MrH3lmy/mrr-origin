package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
@Transactional
class BillingLedgerUpsertService {

    /** Namespaces this service's Postgres advisory locks; distinct from StripeConnectionService's. */
    private static final int SUBSCRIPTION_LOCK_NAMESPACE = 914_101;

    private final JdbcClient jdbc;

    BillingLedgerUpsertService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void upsertCustomer(UUID workspaceId, ParsedCustomer customer, long sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, currency, deleted, provider_created_at,
                             source, source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeCustomerId, :currency, :deleted, :providerCreatedAt,
                             :source, :sourceVersion, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_customer_id) DO UPDATE SET
                            currency = EXCLUDED.currency,
                            deleted = EXCLUDED.deleted,
                            provider_created_at = EXCLUDED.provider_created_at,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_customers.source_version <= EXCLUDED.source_version
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", customer.stripeCustomerId())
                .param("currency", customer.currency())
                .param("deleted", customer.deleted())
                .param("providerCreatedAt", customer.providerCreatedAt())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion)
                .param("updatedAt", now)
                .update();

        customer.discount().ifPresent(discount -> upsertDiscount(workspaceId, discount, sourceVersion, source));
    }

    void upsertPrice(UUID workspaceId, ParsedPrice price, long sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_prices
                            (id, workspace_id, stripe_price_id, stripe_product_id, currency, unit_amount,
                             billing_scheme, type, recurring_interval, recurring_interval_count, active,
                             source, source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripePriceId, :stripeProductId, :currency, :unitAmount,
                             :billingScheme, :type, :recurringInterval, :recurringIntervalCount, :active,
                             :source, :sourceVersion, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_price_id) DO UPDATE SET
                            stripe_product_id = EXCLUDED.stripe_product_id,
                            currency = EXCLUDED.currency,
                            unit_amount = EXCLUDED.unit_amount,
                            billing_scheme = EXCLUDED.billing_scheme,
                            type = EXCLUDED.type,
                            recurring_interval = EXCLUDED.recurring_interval,
                            recurring_interval_count = EXCLUDED.recurring_interval_count,
                            active = EXCLUDED.active,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_prices.source_version <= EXCLUDED.source_version
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
                .param("active", price.active())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion)
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
            UUID workspaceId, ParsedSubscription subscription, long sourceVersion, BillingLedgerSource source) {
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
                             source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeSubscriptionId, :stripeCustomerId, :status, :currency,
                             :currentPeriodStart, :currentPeriodEnd, :cancelAtPeriodEnd, :cancelAt,
                             :canceledAt, :endedAt, :trialStart, :trialEnd, :collectionMethod, :source,
                             :sourceVersion, :updatedAt)
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
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_subscriptions.source_version <= EXCLUDED.source_version
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
                .param("sourceVersion", sourceVersion)
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
                                 new_status, source, source_version)
                            VALUES
                                (:id, :workspaceId, :subscriptionId, :stripeSubscriptionId, :previousStatus,
                                 :newStatus, :source, :sourceVersion)
                            ON CONFLICT (workspace_id, stripe_subscription_id, source_version) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID())
                    .param("workspaceId", workspaceId)
                    .param("subscriptionId", applied.id())
                    .param("stripeSubscriptionId", subscription.stripeSubscriptionId())
                    .param("previousStatus", previousStatus)
                    .param("newStatus", applied.newStatus())
                    .param("source", source.name())
                    .param("sourceVersion", sourceVersion)
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
    }

    private void replaceSubscriptionItems(
            UUID workspaceId, UUID subscriptionId, java.util.List<ParsedSubscriptionItem> items, long sourceVersion) {
        jdbc.sql("DELETE FROM billing_subscription_items WHERE subscription_id = :subscriptionId")
                .param("subscriptionId", subscriptionId)
                .update();
        OffsetDateTime now = now();
        for (ParsedSubscriptionItem item : items) {
            jdbc.sql(
                            """
                            INSERT INTO billing_subscription_items
                                (id, workspace_id, subscription_id, stripe_subscription_item_id, stripe_price_id,
                                 quantity, source_version, updated_at)
                            VALUES
                                (:id, :workspaceId, :subscriptionId, :stripeItemId, :stripePriceId, :quantity,
                                 :sourceVersion, :updatedAt)
                            ON CONFLICT (workspace_id, stripe_subscription_item_id) DO UPDATE SET
                                subscription_id = EXCLUDED.subscription_id,
                                stripe_price_id = EXCLUDED.stripe_price_id,
                                quantity = EXCLUDED.quantity,
                                source_version = EXCLUDED.source_version,
                                updated_at = EXCLUDED.updated_at
                            WHERE billing_subscription_items.source_version <= EXCLUDED.source_version
                            """)
                    .param("id", UUID.randomUUID())
                    .param("workspaceId", workspaceId)
                    .param("subscriptionId", subscriptionId)
                    .param("stripeItemId", item.stripeSubscriptionItemId())
                    .param("stripePriceId", item.stripePriceId())
                    .param("quantity", item.quantity())
                    .param("sourceVersion", sourceVersion)
                    .param("updatedAt", now)
                    .update();
        }
    }

    void upsertInvoice(UUID workspaceId, ParsedInvoice invoice, long sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_invoices
                            (id, workspace_id, stripe_invoice_id, stripe_customer_id, stripe_subscription_id,
                             status, currency, amount_due, amount_paid, amount_remaining, period_start,
                             period_end, provider_created_at, source, source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeInvoiceId, :stripeCustomerId, :stripeSubscriptionId,
                             :status, :currency, :amountDue, :amountPaid, :amountRemaining, :periodStart,
                             :periodEnd, :providerCreatedAt, :source, :sourceVersion, :updatedAt)
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
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_invoices.source_version <= EXCLUDED.source_version
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
                .param("sourceVersion", sourceVersion)
                .param("updatedAt", now)
                .update();
    }

    void upsertPayment(UUID workspaceId, ParsedPayment payment, long sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_payments
                            (id, workspace_id, stripe_charge_id, stripe_customer_id, stripe_invoice_id, amount,
                             currency, status, paid, refunded, amount_refunded, provider_created_at, source,
                             source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeChargeId, :stripeCustomerId, :stripeInvoiceId, :amount,
                             :currency, :status, :paid, :refunded, :amountRefunded, :providerCreatedAt, :source,
                             :sourceVersion, :updatedAt)
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
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_payments.source_version <= EXCLUDED.source_version
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
                .param("sourceVersion", sourceVersion)
                .param("updatedAt", now)
                .update();
    }

    void upsertRefund(UUID workspaceId, ParsedRefund refund, long sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_refunds
                            (id, workspace_id, stripe_refund_id, stripe_charge_id, amount, currency, status,
                             reason, provider_created_at, source, source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeRefundId, :stripeChargeId, :amount, :currency, :status,
                             :reason, :providerCreatedAt, :source, :sourceVersion, :updatedAt)
                        ON CONFLICT (workspace_id, stripe_refund_id) DO UPDATE SET
                            stripe_charge_id = EXCLUDED.stripe_charge_id,
                            amount = EXCLUDED.amount,
                            currency = EXCLUDED.currency,
                            status = EXCLUDED.status,
                            reason = EXCLUDED.reason,
                            provider_created_at = EXCLUDED.provider_created_at,
                            source = EXCLUDED.source,
                            source_version = EXCLUDED.source_version,
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_refunds.source_version <= EXCLUDED.source_version
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
                .param("sourceVersion", sourceVersion)
                .param("updatedAt", now)
                .update();
    }

    void upsertDiscount(UUID workspaceId, ParsedDiscount discount, long sourceVersion, BillingLedgerSource source) {
        OffsetDateTime now = now();
        jdbc.sql(
                        """
                        INSERT INTO billing_discounts
                            (id, workspace_id, stripe_discount_id, stripe_customer_id, stripe_subscription_id,
                             stripe_subscription_item_id, stripe_coupon_id, percent_off, amount_off, currency,
                             start_at, end_at, deleted, source, source_version, updated_at)
                        VALUES
                            (:id, :workspaceId, :stripeDiscountId, :stripeCustomerId, :stripeSubscriptionId,
                             :stripeSubscriptionItemId, :stripeCouponId, :percentOff, :amountOff, :currency,
                             :startAt, :endAt, :deleted, :source, :sourceVersion, :updatedAt)
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
                            updated_at = EXCLUDED.updated_at
                        WHERE billing_discounts.source_version <= EXCLUDED.source_version
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
                .param("endAt", discount.endAt())
                .param("deleted", discount.deleted())
                .param("source", source.name())
                .param("sourceVersion", sourceVersion)
                .param("updatedAt", now)
                .update();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record SubscriptionUpsertOutcome(UUID id, String newStatus) {}
}
