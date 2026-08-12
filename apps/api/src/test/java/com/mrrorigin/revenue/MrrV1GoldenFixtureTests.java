package com.mrrorigin.revenue;

import static java.math.RoundingMode.HALF_UP;
import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MrrV1GoldenFixtureTests {

    private static final Set<String> REASON_CODES = Set.of(
            "UNKNOWN_EFFECTIVE_AT",
            "UNKNOWN_CURRENCY",
            "UNSUPPORTED_INTERVAL",
            "UNSUPPORTED_QUANTITY",
            "UNSUPPORTED_USAGE_PRICING",
            "UNSUPPORTED_DISCOUNT",
            "DISCOUNT_CURRENCY_MISMATCH",
            "AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION",
            "MIXED_CURRENCY_SUBSCRIPTION",
            "CROSS_CURRENCY_AGGREGATION",
            "ZERO_STARTING_MRR_FOR_NRR");

    private static JsonNode fixture;

    @BeforeAll
    static void loadProviderNeutralFixture() throws IOException {
        try (var input = MrrV1GoldenFixtureTests.class.getResourceAsStream("/golden/mrr-v1.json")) {
            assertThat(input).as("golden fixture exists").isNotNull();
            fixture = new ObjectMapper().readTree(input);
        }
    }

    @Test
    void snapshotCasesProveIntervalsQuantitiesDiscountsStatesRoundingAndMinorUnits() {
        assertThat(fixture.path("policyVersion").asText()).isEqualTo("mrr-v1");

        for (JsonNode testCase : fixture.path("snapshotCases")) {
            assertThat(normalizeSnapshot(testCase))
                    .as(testCase.path("id").asText())
                    .isEqualTo(testCase.path("expectedMrr").asLong());
            assertThat(testCase.path("currency").asText(fixture.path("currency").asText()))
                    .as("explicit or fixture-default ISO currency")
                    .matches("[A-Z]{3}");
        }

        assertThat(caseIds("snapshotCases"))
                .contains(
                        "trial-is-zero",
                        "incomplete-is-zero",
                        "incomplete-expired-is-zero",
                        "monthly-quantity",
                        "multi-month",
                        "annual-round-half-up",
                        "multi-year",
                        "percent-discount",
                        "fixed-discount-clamped",
                        "past-due-retained",
                        "unpaid-is-zero",
                        "paused-is-zero",
                        "pause-collection-retains-mrr",
                        "canceled-is-zero",
                        "multi-item-round-once",
                        "zero-decimal-jpy");
    }

    @Test
    void movementCasesProveCustomerLevelClassificationAndEffectiveDateTriggers() {
        for (JsonNode testCase : fixture.path("movementCases")) {
            long before = testCase.path("before").asLong();
            long after = testCase.path("after").asLong();
            String expectedType = classify(before, after, testCase.path("everPositiveBefore").asBoolean());

            assertThat(expectedType).as(testCase.path("id").asText()).isEqualTo(testCase.path("expectedType").asText());
            assertThat(Math.abs(after - before)).isEqualTo(testCase.path("expectedAmount").asLong());
            assertThat(testCase.path("effectiveAt").asText()).endsWith("Z");
            assertThat(testCase.path("trigger").asText()).isNotBlank();
        }

        assertThat(caseIds("movementCases"))
                .contains(
                        "trial-converts-new",
                        "quantity-expansion",
                        "discount-starts-contraction",
                        "one-of-two-subscriptions-cancels",
                        "period-end-churn",
                        "resume-reactivation",
                        "discount-ends-expansion",
                        "immediate-cancellation");
    }

    @Test
    void invoicesPaymentsRefundsRetriesAndDuplicateInputsCannotCreateMovements() {
        assertThat(fixture.path("movementCases"))
                .filteredOn(testCase -> Set.of("refund", "payment_failed", "invoice_paid", "duplicate")
                        .contains(testCase.path("trigger").asText()))
                .allSatisfy(testCase -> {
                    assertThat(testCase.path("expectedType").asText()).isEqualTo("NONE");
                    assertThat(testCase.path("expectedAmount").asLong()).isZero();
                    assertThat(testCase.path("before").asLong()).isEqualTo(testCase.path("after").asLong());
                });
    }

    @Test
    void retainedMrrAndNrrCasesUseFixedCohortsAndIntegerBasisPoints() {
        JsonNode retained = findCase("retentionCases", "retained-mrr-includes-active-expansion-and-reactivation");
        long retainedMrr = 0;
        for (JsonNode customerMrr : retained.path("customerMrrAtAge")) {
            retainedMrr += customerMrr.asLong();
        }
        assertThat(retainedMrr).isEqualTo(retained.path("expectedRetainedMrr").asLong());

        for (JsonNode testCase : fixture.path("retentionCases")) {
            if (!testCase.has("startingMrr")) {
                continue;
            }
            long numerator = testCase.path("startingMrr").asLong()
                    + testCase.path("expansion").asLong()
                    - testCase.path("contraction").asLong()
                    - testCase.path("churn").asLong();
            long basisPoints = BigDecimal.valueOf(numerator)
                    .multiply(BigDecimal.valueOf(10_000))
                    .divide(BigDecimal.valueOf(testCase.path("startingMrr").asLong()), 0, HALF_UP)
                    .longValueExact();
            assertThat(basisPoints).as(testCase.path("id").asText()).isEqualTo(testCase.path("expectedNrrBasisPoints").asLong());
        }
    }

    @Test
    void sameTimestampChangesAreNettedFromCompleteCustomerState() {
        JsonNode testCase = findCase("movementCases", "same-timestamp-customer-netting");
        long before = 0;
        long after = 0;
        for (JsonNode change : testCase.path("changes")) {
            before += change.path("before").asLong();
            after += change.path("after").asLong();
        }

        assertThat(before).isEqualTo(testCase.path("before").asLong());
        assertThat(after).isEqualTo(testCase.path("after").asLong());
        assertThat(classify(before, after, testCase.path("everPositiveBefore").asBoolean()))
                .isEqualTo("NONE");
    }

    @Test
    void currencySwitchesClassifyIndependentlyWithoutNettingAmounts() {
        for (JsonNode testCase : fixture.path("currencyMovementCases")) {
            Set<String> currencies = new HashSet<>();
            for (JsonNode movement : testCase.path("movements")) {
                assertThat(currencies.add(movement.path("currency").asText()))
                        .as(testCase.path("id").asText())
                        .isTrue();
                long before = movement.path("before").asLong();
                long after = movement.path("after").asLong();
                assertThat(classify(before, after, movement.path("everPositiveBefore").asBoolean()))
                        .isEqualTo(movement.path("expectedType").asText());
                assertThat(Math.abs(after - before)).isEqualTo(movement.path("expectedAmount").asLong());
            }
            assertThat(currencies).containsExactlyInAnyOrder("USD", "EUR");
        }
    }

    @Test
    void everyUnsupportedCaseFailsWithAUniqueStableReasonInsteadOfAValue() {
        Set<String> seenReasons = new HashSet<>();
        for (JsonNode testCase : fixture.path("unsupportedCases")) {
            assertThat(testCase.has("expectedMrr")).as(testCase.path("id").asText()).isFalse();
            assertThat(testCase.has("expectedAmount")).as(testCase.path("id").asText()).isFalse();
            assertThat(REASON_CODES).contains(testCase.path("reason").asText());
            assertThat(seenReasons.add(testCase.path("reason").asText())).isTrue();
        }
        assertThat(seenReasons).containsExactlyInAnyOrderElementsOf(REASON_CODES);
    }

    private static long normalizeSnapshot(JsonNode testCase) {
        if (Set.of("trialing", "incomplete", "incomplete_expired", "unpaid", "paused", "canceled")
                .contains(testCase.path("state").asText())) {
            return 0;
        }

        BigDecimal exactSubscriptionMrr = BigDecimal.ZERO;
        if (testCase.has("items")) {
            for (JsonNode item : testCase.path("items")) {
                exactSubscriptionMrr = exactSubscriptionMrr.add(normalizedItemContribution(item));
            }
        } else {
            exactSubscriptionMrr = normalizedItemContribution(testCase);
        }
        return exactSubscriptionMrr.setScale(0, HALF_UP).longValueExact();
    }

    private static BigDecimal normalizedItemContribution(JsonNode item) {
        BigDecimal discountedPeriodAmount = BigDecimal.valueOf(item.path("periodAmount").asLong())
                .multiply(BigDecimal.valueOf(item.path("quantity").asLong()));
        if (item.has("discountPercent")) {
            discountedPeriodAmount = discountedPeriodAmount.multiply(
                    BigDecimal.valueOf(100 - item.path("discountPercent").asLong()).movePointLeft(2));
        }
        if (item.has("discountAmount")) {
            discountedPeriodAmount =
                    discountedPeriodAmount.subtract(BigDecimal.valueOf(item.path("discountAmount").asLong()));
        }
        discountedPeriodAmount = discountedPeriodAmount.max(BigDecimal.ZERO);

        long months = switch (item.path("interval").asText()) {
            case "month" -> item.path("intervalCount").asLong();
            case "year" -> Math.multiplyExact(12, item.path("intervalCount").asLong());
            default -> throw new AssertionError("unsupported interval accidentally entered supported snapshots");
        };
        return discountedPeriodAmount.divide(BigDecimal.valueOf(months), 20, HALF_UP);
    }

    private static String classify(long before, long after, boolean everPositiveBefore) {
        if (before == after) return "NONE";
        if (before == 0) return everPositiveBefore ? "REACTIVATION" : "NEW";
        if (after == 0) return "CHURN";
        return after > before ? "EXPANSION" : "CONTRACTION";
    }

    private static Set<String> caseIds(String section) {
        Set<String> ids = new HashSet<>();
        fixture.path(section).forEach(testCase -> assertThat(ids.add(testCase.path("id").asText())).isTrue());
        return ids;
    }

    private static JsonNode findCase(String section, String id) {
        for (JsonNode testCase : fixture.path(section)) {
            if (id.equals(testCase.path("id").asText())) return testCase;
        }
        throw new AssertionError("missing case " + id);
    }
}
