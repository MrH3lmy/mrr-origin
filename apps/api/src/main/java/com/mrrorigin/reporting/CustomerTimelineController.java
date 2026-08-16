package com.mrrorigin.reporting;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * #24's single-customer detail and evidence timeline. A customer ID that this project does not
 * currently own resolves to 404, identically to one that was never observed at all, so an evidence
 * lookup can never confirm a customer's existence in another project.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/customers/{stripeCustomerId}")
public class CustomerTimelineController {

    private final CustomerTimelineService timelineService;
    private final WorkspaceContext workspaceContext;

    public CustomerTimelineController(CustomerTimelineService timelineService, WorkspaceContext workspaceContext) {
        this.timelineService = timelineService;
        this.workspaceContext = workspaceContext;
    }

    @GetMapping("/timeline")
    public TimelineResponse timeline(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable @NotBlank String stripeCustomerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(CustomerTimelineService.MAX_LIMIT) Integer limit) {
        workspaceContext.requireMembership(workspaceId);
        return timelineService
                .get(workspaceId, projectId, stripeCustomerId, cursor, limit)
                .map(TimelineResponse::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Customer not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", error.getMessage()));
    }

    public record TimelineResponse(
            CustomerTimelineService.CustomerDetail detail,
            List<CustomerTimelineService.TimelineEntry> entries,
            String nextCursor) {
        static TimelineResponse from(CustomerTimelineService.CustomerTimeline timeline) {
            return new TimelineResponse(timeline.detail(), timeline.entries(), timeline.nextCursor());
        }
    }
}
