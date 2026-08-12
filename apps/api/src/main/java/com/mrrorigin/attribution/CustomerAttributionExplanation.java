package com.mrrorigin.attribution;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CustomerAttributionExplanation(
        UUID workspaceId, UUID projectId, String customerId, UUID movementId,
        UUID acquisitionMovementId, String movementType, OffsetDateTime movementAt,
        String modelVersion, Evidence firstTouch, Evidence lastTouch, UUID customerLinkEvidenceId,
        String confidence, String unattributedReason, List<String> sourceReferences,
        OffsetDateTime calculatedAt) {
    public record Evidence(UUID touchpointId, String source, String campaign, String landingPage) {}
}
