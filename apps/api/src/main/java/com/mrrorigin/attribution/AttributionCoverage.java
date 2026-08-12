package com.mrrorigin.attribution;

import java.util.Map;
import java.util.UUID;

/**
 * Attribution coverage for one (workspace, project, model version), per {@code PRODUCT.md}'s
 * "Attribution coverage" metric: "Share of eligible new customers linked to acceptable acquisition
 * evidence."
 *
 * <p>{@code eligibleNewCustomers} (the denominator) counts distinct customers in this project's
 * recalculation scope that have a current-version NEW MRR movement. Scope is the same as the batch
 * recalculation job: currently linked customers plus customers for which the project already has an
 * attribution history. A customer is counted once even if multiple NEW movements exist (for example
 * across currencies). {@code attributedNewCustomers} (the numerator) is the subset whose acquisition
 * result for {@code modelVersion} has {@code confidence = STRONG}.
 *
 * <p>{@code exclusionReasonCounts} breaks the gap between numerator and denominator down by reason.
 * {@code NO_ACTIVE_LINK} and {@code NO_ELIGIBLE_TOUCHPOINT} are attribution outcomes from ADR-0005.
 * {@code NOT_RECALCULATED} is a coverage-only operational reason used while an eligible customer has
 * not yet been processed for the requested model version. Keeping those customers in the denominator
 * prevents a partial recalculation from temporarily overstating product coverage.
 */
public record AttributionCoverage(
        UUID workspaceId,
        UUID projectId,
        String modelVersion,
        long eligibleNewCustomers,
        long attributedNewCustomers,
        Map<String, Long> exclusionReasonCounts) {
    public double coverageRatio() {
        return eligibleNewCustomers == 0 ? 0d : (double) attributedNewCustomers / eligibleNewCustomers;
    }
}
