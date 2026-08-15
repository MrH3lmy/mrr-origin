package com.mrrorigin.attribution;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Read-only attribution coverage for the current model version (#22). Exposes {@link
 * AttributionApplicationService#coverage}, which #19 already implements and covers with fixtures;
 * this controller adds no calculation logic of its own. Any workspace member may read it.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/attribution/coverage")
public class AttributionCoverageController {

    private final AttributionApplicationService attributionService;
    private final WorkspaceContext workspaceContext;

    public AttributionCoverageController(
            AttributionApplicationService attributionService, WorkspaceContext workspaceContext) {
        this.attributionService = attributionService;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping
    public CoverageResponse coverage(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        return CoverageResponse.from(
                attributionService.coverage(workspaceId, projectId, AttributionV1Engine.MODEL_VERSION));
    }

    public record CoverageResponse(
            String modelVersion,
            long eligibleNewCustomers,
            long attributedNewCustomers,
            double coverageRatio,
            Map<String, Long> exclusionReasonCounts) {
        static CoverageResponse from(AttributionCoverage coverage) {
            return new CoverageResponse(
                    coverage.modelVersion(),
                    coverage.eligibleNewCustomers(),
                    coverage.attributedNewCustomers(),
                    coverage.coverageRatio(),
                    coverage.exclusionReasonCounts());
        }
    }
}
