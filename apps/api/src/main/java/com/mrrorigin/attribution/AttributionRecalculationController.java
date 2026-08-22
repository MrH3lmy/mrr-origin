package com.mrrorigin.attribution;

import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Operator-facing recovery surface for #84's gap: {@link AttributionRecalculationService} already
 * implements bounded/resumable/idempotent recalculation, but until now it could only be invoked by
 * calling the Spring service directly. This controller adds no recovery logic of its own -- it is
 * HTTP/auth/validation only, delegating every operation to the existing service and preserving its
 * semantics (bounded batches, durable checkpoint, restart refusing an active run).
 *
 * <p>All three operations require {@link WorkspaceContext#requireManager}, not just membership:
 * unlike read-only attribution surfaces such as {@link AttributionCoverageController}, this is an
 * operator/recovery surface (#84's acceptance criteria call status itself "manager/operator-authorized"),
 * and the mutating operations trigger real recalculation work.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/attribution-recalculation")
public class AttributionRecalculationController {

    private static final int DEFAULT_MAX_CUSTOMERS = 100;
    private static final int MAX_MAX_CUSTOMERS = 500;

    private final WorkspaceContext workspaceContext;
    private final AttributionProjectAccess projectAccess;
    private final AttributionRecalculationService recalculation;

    public AttributionRecalculationController(
            WorkspaceContext workspaceContext,
            AttributionProjectAccess projectAccess,
            AttributionRecalculationService recalculation) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.recalculation = recalculation;
    }

    @GetMapping
    public StatusResponse status(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return recalculation.status(workspaceId, projectId)
                .map(StatusResponse::from)
                .orElseGet(StatusResponse::notStarted);
    }

    @PostMapping("/resume")
    public ResumeResponse resume(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "" + DEFAULT_MAX_CUSTOMERS) @Min(1) @Max(MAX_MAX_CUSTOMERS) int maxCustomers) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return ResumeResponse.from(recalculation.runBatch(workspaceId, projectId, maxCustomers));
    }

    @PostMapping("/restart")
    public StatusResponse restart(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        try {
            recalculation.restart(workspaceId, projectId);
        } catch (IllegalStateException stillRunningOrMissing) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, stillRunningOrMissing.getMessage());
        }
        return recalculation.status(workspaceId, projectId).map(StatusResponse::from).orElseThrow();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Void> invalidRequest() {
        return ResponseEntity.badRequest().build();
    }

    public record StatusResponse(
            String status, String cursorCustomerId, long customersProcessed, boolean complete, String modelVersion) {

        static StatusResponse from(AttributionRecalculationService.Run run) {
            return new StatusResponse(
                    run.status(), run.cursor(), run.processed(), "COMPLETED".equals(run.status()), AttributionV1Engine.MODEL_VERSION);
        }

        static StatusResponse notStarted() {
            return new StatusResponse("NOT_STARTED", null, 0, false, AttributionV1Engine.MODEL_VERSION);
        }
    }

    public record ResumeResponse(
            int customersProcessedThisBatch,
            long totalCustomersProcessed,
            String cursorCustomerId,
            boolean complete,
            String status) {

        static ResumeResponse from(AttributionRecalculationService.BatchOutcome outcome) {
            return new ResumeResponse(
                    outcome.customersProcessedThisBatch(),
                    outcome.totalCustomersProcessed(),
                    outcome.cursorCustomerId(),
                    outcome.complete(),
                    outcome.complete() ? "COMPLETED" : "RUNNING");
        }
    }
}
