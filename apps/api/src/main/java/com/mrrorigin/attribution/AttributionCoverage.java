package com.mrrorigin.attribution;

import java.util.Map;
import java.util.UUID;

/**
 * Attribution coverage for one (workspace, project, model version), per {@code PRODUCT.md}'s
 * "Attribution coverage" metric: "Share of eligible new customers linked to acceptable acquisition
 * evidence."
 *
 * <p>{@code eligibleNewCustomers} (the denominator) counts every stored attribution result for a
 * NEW MRR movement in scope -- i.e. every customer this project has already recalculated under
 * {@code modelVersion}, whether the outcome was attributed or not. {@code attributedNewCustomers}
 * (the numerator) is the subset with {@code confidence = STRONG} (currently the only acceptable
 * evidence tier that production writes; Verified and Moderate are reserved by ADR-0005).
 *
 * <p>{@code exclusionReasonCounts} breaks the gap between numerator and denominator down by
 * {@code unattributed_reason}, the two codes ADR-0005 defines: {@code NO_ACTIVE_LINK} (no
 * non-superseded {@code stripe_customer_links} row for the customer) and
 * {@code NO_ELIGIBLE_TOUCHPOINT} (an active link exists, but its touchpoint pool has nothing inside
 * the attribution window). A customer never recalculated at all is outside both the numerator and
 * denominator -- coverage answers "of what we've evaluated," not "of everyone who will ever exist."
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
