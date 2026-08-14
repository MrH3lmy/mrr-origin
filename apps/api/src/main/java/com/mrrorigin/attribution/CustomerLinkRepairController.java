package com.mrrorigin.attribution;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.identity.StripeCustomerLinkException;
import com.mrrorigin.identity.StripeCustomerLinkingService.LinkOutcome;
import com.mrrorigin.workspace.WorkspaceContext;

/**
 * #20's manual repair surface: explicit create-or-correct linking plus its audit trail. Creating
 * or correcting a link ({@link #repair}) is gated on workspace management permission inside {@link
 * CustomerLinkRepairService} (via {@code StripeCustomerLinkingService#repair}); reading the audit
 * trail ({@link #history}) only requires workspace membership.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue/repairs")
public class CustomerLinkRepairController {

    private final CustomerLinkRepairService repairService;
    private final CustomerLinkRepairAuditService auditService;
    private final WorkspaceContext workspaceContext;

    public CustomerLinkRepairController(
            CustomerLinkRepairService repairService,
            CustomerLinkRepairAuditService auditService,
            WorkspaceContext workspaceContext) {
        this.repairService = repairService;
        this.auditService = auditService;
        this.workspaceContext = workspaceContext;
    }

    @PostMapping
    public RepairResponse repair(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @Valid @RequestBody RepairRequest request) {
        return RepairResponse.from(
                repairService.repair(workspaceId, projectId, request.externalUserId(), request.stripeCustomerId()));
    }

    @GetMapping
    public List<AuditEntryResponse> history(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam @NotBlank @Size(max = 255) String stripeCustomerId,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        workspaceContext.requireMembership(workspaceId);
        return auditService.history(workspaceId, projectId, stripeCustomerId, limit).stream()
                .map(AuditEntryResponse::from)
                .toList();
    }

    @ExceptionHandler(StripeCustomerLinkException.class)
    ResponseEntity<Map<String, String>> linkError(StripeCustomerLinkException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationError() {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request", "message", "Request validation failed"));
    }

    public record RepairRequest(
            @NotBlank @Size(max = 160) String externalUserId, @NotBlank @Size(max = 255) String stripeCustomerId) {}

    /** Mirrors #20's "Identity linked -> recalculating -> attribution updated" UI status contract. */
    public record RepairResponse(
            LinkOutcome link,
            String actionType,
            String previousIdentityStripeCustomerId,
            List<CustomerAttributionExplanation> targetCustomerAttribution,
            String displacedCustomerId,
            List<CustomerAttributionExplanation> displacedCustomerAttribution) {

        static RepairResponse from(CustomerLinkRepairService.RepairResult result) {
            var outcome = result.linkOutcome();
            String previousIdentityCustomerId =
                    outcome.previousIdentityLink() == null ? null : outcome.previousIdentityLink().stripeCustomerId();
            return new RepairResponse(
                    outcome.link(),
                    outcome.actionType(),
                    previousIdentityCustomerId,
                    result.targetCustomerAttribution(),
                    result.displacedCustomerId(),
                    result.displacedCustomerAttribution());
        }
    }

    public record AuditEntryResponse(
            UUID id,
            String externalUserId,
            String actionType,
            UUID newLinkId,
            UUID previousIdentityLinkId,
            UUID previousCustomerLinkId,
            String actorSubjectId,
            OffsetDateTime createdAt) {

        static AuditEntryResponse from(CustomerLinkRepairAuditService.AuditEntry entry) {
            return new AuditEntryResponse(
                    entry.id(),
                    entry.externalUserId(),
                    entry.actionType(),
                    entry.newLinkId(),
                    entry.previousIdentityLinkId(),
                    entry.previousCustomerLinkId(),
                    entry.actorSubjectId(),
                    entry.createdAt());
        }
    }
}
