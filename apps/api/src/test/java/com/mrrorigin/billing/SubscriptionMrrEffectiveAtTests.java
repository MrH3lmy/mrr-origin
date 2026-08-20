package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mrrorigin.billing.StripeBillingObjects.ParsedSubscription;

/** Pure unit coverage of ADR-0010's effective_at priority order; no Spring/DB needed. */
class SubscriptionMrrEffectiveAtTests {

    private static final OffsetDateTime PROVIDER_AT = OffsetDateTime.parse("2026-06-01T00:00:00Z");
    private static final OffsetDateTime TRIAL_START = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final OffsetDateTime TRIAL_END = OffsetDateTime.parse("2026-01-15T00:00:00Z");
    private static final OffsetDateTime PERIOD_START = OffsetDateTime.parse("2026-02-01T00:00:00Z");
    private static final OffsetDateTime CANCELED_AT = OffsetDateTime.parse("2026-03-01T00:00:00Z");
    private static final OffsetDateTime ENDED_AT = OffsetDateTime.parse("2026-03-02T00:00:00Z");

    @Test
    void trialToPaidTransitionUsesTrialEnd() {
        ParsedSubscription sub = subscription("active", TRIAL_START, TRIAL_END, null, null, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve("trialing", sub, PROVIDER_AT)).isEqualTo(TRIAL_END);
    }

    @Test
    void trialToPastDueTransitionAlsoUsesTrialEnd() {
        ParsedSubscription sub = subscription("past_due", TRIAL_START, TRIAL_END, null, null, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve("trialing", sub, PROVIDER_AT)).isEqualTo(TRIAL_END);
    }

    @Test
    void enteringCanceledPrefersCanceledAt() {
        ParsedSubscription sub = subscription("canceled", null, null, null, CANCELED_AT, ENDED_AT);
        assertThat(SubscriptionMrrEffectiveAt.resolve("active", sub, PROVIDER_AT)).isEqualTo(CANCELED_AT);
    }

    @Test
    void enteringCanceledFallsBackToEndedAtWhenCanceledAtMissing() {
        ParsedSubscription sub = subscription("canceled", null, null, null, null, ENDED_AT);
        assertThat(SubscriptionMrrEffectiveAt.resolve("active", sub, PROVIDER_AT)).isEqualTo(ENDED_AT);
    }

    @Test
    void enteringCanceledFallsBackToProviderAtWhenNeitherFieldIsSet() {
        ParsedSubscription sub = subscription("canceled", null, null, null, null, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve("active", sub, PROVIDER_AT)).isEqualTo(PROVIDER_AT);
    }

    @Test
    void alreadyCanceledStayingCanceledIsNotAnEnteringTransition() {
        ParsedSubscription sub = subscription("canceled", null, null, null, CANCELED_AT, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve("canceled", sub, PROVIDER_AT)).isEqualTo(PROVIDER_AT);
    }

    @Test
    void firstObservedActiveStateUsesCurrentPeriodStart() {
        ParsedSubscription sub = subscription("active", null, null, PERIOD_START, null, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve(null, sub, PROVIDER_AT)).isEqualTo(PERIOD_START);
    }

    @Test
    void firstObservedTrialingStateUsesTrialStart() {
        ParsedSubscription sub = subscription("trialing", TRIAL_START, null, null, null, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve(null, sub, PROVIDER_AT)).isEqualTo(TRIAL_START);
    }

    @Test
    void statusUnchangedFallsBackToProviderDeclaredSecond() {
        ParsedSubscription sub = subscription("active", null, null, PERIOD_START, null, null);
        assertThat(SubscriptionMrrEffectiveAt.resolve("active", sub, PROVIDER_AT)).isEqualTo(PROVIDER_AT);
    }

    private static ParsedSubscription subscription(
            String status,
            OffsetDateTime trialStart,
            OffsetDateTime trialEnd,
            OffsetDateTime currentPeriodStart,
            OffsetDateTime canceledAt,
            OffsetDateTime endedAt) {
        return new ParsedSubscription(
                "sub_test",
                "cus_test",
                status,
                "usd",
                currentPeriodStart,
                null,
                false,
                null,
                canceledAt,
                endedAt,
                trialStart,
                trialEnd,
                "charge_automatically",
                List.of(),
                List.of());
    }
}
