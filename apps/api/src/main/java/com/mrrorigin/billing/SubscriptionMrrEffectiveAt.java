package com.mrrorigin.billing;

import java.time.OffsetDateTime;

import com.mrrorigin.billing.StripeBillingObjects.ParsedSubscription;

/**
 * Derives the provider-declared instant a subscription's new state took effect, per ADR-0004 and
 * ADR-0010. {@code RevenueCalculationService} requires this value -- it never guesses one itself
 * -- so this is the one place billing decides which of a subscription's own timestamp fields best
 * represents "when this normalized change was effective," in the priority order ADR-0004 documents:
 * a more specific provider field first, the event's own provider-declared second as the sanctioned
 * fallback last. It never invents a new MRR business rule; it only routes existing provider data.
 */
final class SubscriptionMrrEffectiveAt {

    private SubscriptionMrrEffectiveAt() {}

    /**
     * @param previousStatus this subscription's status before this normalization applied, or
     *     {@code null} if this is the first state ever recorded locally for it.
     * @param providerAt the event's own provider-declared instant (the webhook's Stripe {@code
     *     created} second, or backfill's conservative last-fully-elapsed provider second) -- used
     *     only when no more specific field-level timestamp applies.
     */
    static OffsetDateTime resolve(String previousStatus, ParsedSubscription subscription, OffsetDateTime providerAt) {
        String status = subscription.status();

        boolean enteringPaidFromTrial =
                "trialing".equals(previousStatus) && ("active".equals(status) || "past_due".equals(status));
        if (enteringPaidFromTrial && subscription.trialEnd() != null) {
            return subscription.trialEnd();
        }

        boolean enteringCanceled = "canceled".equals(status) && !"canceled".equals(previousStatus);
        if (enteringCanceled) {
            if (subscription.canceledAt() != null) {
                return subscription.canceledAt();
            }
            if (subscription.endedAt() != null) {
                return subscription.endedAt();
            }
        }

        boolean firstObservedState = previousStatus == null;
        if (firstObservedState) {
            if ("trialing".equals(status) && subscription.trialStart() != null) {
                return subscription.trialStart();
            }
            if (("active".equals(status) || "past_due".equals(status)) && subscription.currentPeriodStart() != null) {
                return subscription.currentPeriodStart();
            }
        }

        return providerAt;
    }
}
