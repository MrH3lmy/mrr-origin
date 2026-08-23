package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * #92 review fix: {@code max-customers-per-scope} must share the same upper bound
 * ({@link AttributionRecalculationService#MAX_BATCH_SIZE}) as {@link
 * AttributionRecalculationController}'s {@code resume} endpoint, not just a >= 1 floor. Plain
 * constructor-level validation -- no Spring context or database needed.
 */
class AttributionRecalculationSchedulerPropertiesTests {

    @Test
    void rejectsMaxCustomersPerScopeAboveTheSharedUpperBound() {
        assertThatThrownBy(() -> new AttributionRecalculationSchedulerProperties(true, 501, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-customers-per-scope")
                .hasMessageContaining("500");
    }

    @Test
    void acceptsMaxCustomersPerScopeAtTheSharedUpperBound() {
        var properties = new AttributionRecalculationSchedulerProperties(true, 500, 20);
        assertThat(properties.maxCustomersPerScope()).isEqualTo(500);
        assertThat(properties.maxCustomersPerScope()).isEqualTo(AttributionRecalculationService.MAX_BATCH_SIZE);
    }

    @Test
    void rejectsMaxCustomersPerScopeBelowOne() {
        assertThatThrownBy(() -> new AttributionRecalculationSchedulerProperties(true, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-customers-per-scope");
    }

    @Test
    void rejectsMaxScopesPerTickBelowOne() {
        assertThatThrownBy(() -> new AttributionRecalculationSchedulerProperties(true, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-scopes-per-tick");
    }

    @Test
    void nullValuesFallBackToDocumentedDefaults() {
        var properties = new AttributionRecalculationSchedulerProperties(null, null, null);
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.maxCustomersPerScope()).isEqualTo(100);
        assertThat(properties.maxScopesPerTick()).isEqualTo(20);
    }
}
