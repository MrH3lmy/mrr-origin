package com.mrrorigin.reporting;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Builds the relative Sources-screen (#23) path that reconciles to one exact movement-drilldown
 * filter set -- the same filter contract {@link RevenueMovementsService#list} accepts. Shared by
 * #26's weekly summary insights and CSV {@code evidence_link} columns so both features describe a
 * drilldown identically, in one place.
 */
public final class EvidenceLink {
    private EvidenceLink() {}

    public static String path(
            UUID workspaceId,
            UUID projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            String movementType,
            String currency,
            String source,
            boolean sourceUnattributed,
            boolean sourceMissing,
            String campaign,
            boolean campaignMissing,
            String landingPage,
            boolean landingPageMissing) {
        StringBuilder sb = new StringBuilder("/app/")
                .append(workspaceId).append("/projects/").append(projectId).append("/sources?")
                .append("from=").append(enc(from.toString()))
                .append("&to=").append(enc(to.toString()));
        if (movementType != null) sb.append("&movementType=").append(enc(movementType));
        if (currency != null) sb.append("&currency=").append(enc(currency));
        if (source != null) sb.append("&source=").append(enc(source));
        if (sourceUnattributed) sb.append("&sourceUnattributed=true");
        if (sourceMissing) sb.append("&sourceMissing=true");
        if (campaign != null) sb.append("&campaign=").append(enc(campaign));
        if (campaignMissing) sb.append("&campaignMissing=true");
        if (landingPage != null) sb.append("&landingPage=").append(enc(landingPage));
        if (landingPageMissing) sb.append("&landingPageMissing=true");
        return sb.toString();
    }

    /** Path to a single customer's evidence timeline (#24), for the {@code customers-v1} export. */
    public static String customerTimelinePath(UUID workspaceId, UUID projectId, String stripeCustomerId) {
        return "/app/" + workspaceId + "/projects/" + projectId + "/customers/" + enc(stripeCustomerId);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
