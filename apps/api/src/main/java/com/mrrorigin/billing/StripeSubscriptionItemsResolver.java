package com.mrrorigin.billing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import com.mrrorigin.billing.StripeBackfillClient.StripePage;

/**
 * Completes a subscription's item list when its embedded {@code items} page is itself paginated
 * ({@code has_more=true}). Per https://docs.stripe.com/api/subscriptions/object, the embedded page
 * is not guaranteed complete; {@link BillingLedgerUpsertService} replaces a subscription's entire
 * stored item set on every upsert, so normalizing from a partial embedded page would silently drop
 * items the account actually has. Used identically by the backfill and webhook-normalization
 * pipelines so both complete the same way.
 */
@Component
class StripeSubscriptionItemsResolver {

    /** Bounded like every other Stripe pagination loop in this module -- never unbounded. */
    private static final int MAX_SUPPLEMENTAL_PAGES = 50;

    private final StripeBackfillClient client;

    StripeSubscriptionItemsResolver(StripeBackfillClient client) {
        this.client = client;
    }

    /** Raw item objects beyond the subscription's own embedded page; empty if it was already complete. */
    List<JsonNode> resolveSupplementalItems(StripeConnectionMode mode, String stripeAccountId, JsonNode subscription) {
        if (!StripeBillingObjectParser.embeddedItemsHaveMore(subscription)) {
            return List.of();
        }
        String subscriptionId = StripeBillingObjectParser.subscriptionId(subscription);
        String cursor = StripeBillingObjectParser.lastEmbeddedItemId(subscription);

        List<JsonNode> supplemental = new ArrayList<>();
        int pages = 0;
        while (true) {
            StripePage page = client.listSubscriptionItems(mode, stripeAccountId, subscriptionId, cursor);
            supplemental.addAll(page.data());
            if (!page.hasMore()) {
                break;
            }
            cursor = page.lastId();
            if (cursor == null) {
                throw new StripeBackfillException(
                        "Stripe subscription_items page reported has_more=true but supplied no valid cursor for subscription "
                                + subscriptionId);
            }
            if (++pages > MAX_SUPPLEMENTAL_PAGES) {
                throw new StripeBackfillException("Subscription items pagination exceeded its bound for subscription " + subscriptionId);
            }
        }
        return List.copyOf(supplemental);
    }
}
