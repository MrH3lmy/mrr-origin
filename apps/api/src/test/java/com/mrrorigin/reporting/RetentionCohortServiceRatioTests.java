package com.mrrorigin.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Zero-denominator handling for retention percentage and NRR, isolated from the database and HTTP
 * layers. Per ADR-0006, {@code startingMrr = 0} (an empty cohort) must never divide-by-zero or
 * fabricate a zero/100% ratio -- it is explicitly undefined ({@code null}).
 */
class RetentionCohortServiceRatioTests {

    @Test
    void isUndefinedWhenStartingMrrIsZero() {
        assertThat(RetentionCohortService.ratio(0, 0)).isNull();
        assertThat(RetentionCohortService.ratio(500, 0)).isNull();
        assertThat(RetentionCohortService.ratio(-500, 0)).isNull();
    }

    @Test
    void isTheExactQuotientWhenStartingMrrIsPositive() {
        assertThat(RetentionCohortService.ratio(500, 1000)).isEqualTo(0.5);
        assertThat(RetentionCohortService.ratio(1000, 1000)).isEqualTo(1.0);
        assertThat(RetentionCohortService.ratio(0, 1000)).isEqualTo(0.0);
        assertThat(RetentionCohortService.ratio(1500, 1000)).isEqualTo(1.5);
    }
}
