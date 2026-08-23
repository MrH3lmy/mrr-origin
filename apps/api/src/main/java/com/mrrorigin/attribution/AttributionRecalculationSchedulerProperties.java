package com.mrrorigin.attribution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-configurable bounds for {@link AttributionRecalculationScheduler} (#92). Defaults are
 * conservative private-beta values: at most 100 customers per scope per tick (matching PR #89's
 * {@code AttributionRecalculationController} resume default) across at most 20 scopes per tick,
 * ticking every minute. {@code enabled} lets an operator turn the driver off without a redeploy.
 */
@ConfigurationProperties(prefix = "mrrorigin.attribution.recalculation.scheduler")
public record AttributionRecalculationSchedulerProperties(Boolean enabled, Integer maxCustomersPerScope, Integer maxScopesPerTick) {

    private static final int DEFAULT_MAX_CUSTOMERS_PER_SCOPE = 100;
    private static final int DEFAULT_MAX_SCOPES_PER_TICK = 20;

    public AttributionRecalculationSchedulerProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxCustomersPerScope = maxCustomersPerScope == null ? DEFAULT_MAX_CUSTOMERS_PER_SCOPE : maxCustomersPerScope;
        maxScopesPerTick = maxScopesPerTick == null ? DEFAULT_MAX_SCOPES_PER_TICK : maxScopesPerTick;
        if (maxCustomersPerScope < 1) {
            throw new IllegalArgumentException(
                    "mrrorigin.attribution.recalculation.scheduler.max-customers-per-scope must be at least 1");
        }
        if (maxScopesPerTick < 1) {
            throw new IllegalArgumentException(
                    "mrrorigin.attribution.recalculation.scheduler.max-scopes-per-tick must be at least 1");
        }
    }
}
