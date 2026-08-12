package com.mrrorigin.identity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
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

/**
 * Server-side identity bridge per #16: links a project's tracked external-user identity to a
 * workspace's Stripe customer. Called from the founder's own backend, never from the browser
 * tracker, since it deals in Stripe customer IDs.
 */
@RestController
@Validated
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links")
public class StripeCustomerLinkingController {

    private final StripeCustomerLinkingService service;

    public StripeCustomerLinkingController(StripeCustomerLinkingService service) {
        this.service = service;
    }

    @PostMapping
    public LinkResponse link(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @Valid @RequestBody LinkRequest request) {
        return LinkResponse.from(
                service.link(workspaceId, projectId, request.externalUserId(), request.stripeCustomerId()));
    }

    @GetMapping
    public LinkResponse activeLink(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam @NotBlank @Size(max = 160) String externalUserId) {
        return service
                .activeLink(workspaceId, projectId, externalUserId)
                .map(LinkResponse::from)
                .orElseThrow(() -> new StripeCustomerLinkException(
                        HttpStatus.NOT_FOUND, "stripe_customer_link_not_found",
                        "No active Stripe customer link exists for this external user"));
    }

    @ExceptionHandler(StripeCustomerLinkException.class)
    ResponseEntity<Map<String, String>> linkError(StripeCustomerLinkException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationError() {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "invalid_request", "message", "Request validation failed"));
    }

    public record LinkRequest(
            @NotBlank @Size(max = 160) String externalUserId, @NotBlank @Size(max = 255) String stripeCustomerId) {}

    public record LinkResponse(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            String externalUserId,
            String stripeCustomerId,
            String evidenceSource,
            String evidenceReference,
            String linkedBySubjectId,
            OffsetDateTime createdAt) {

        static LinkResponse from(StripeCustomerLinkingService.LinkOutcome outcome) {
            return new LinkResponse(
                    outcome.id(),
                    outcome.workspaceId(),
                    outcome.projectId(),
                    outcome.externalUserId(),
                    outcome.stripeCustomerId(),
                    outcome.evidenceSource(),
                    outcome.evidenceReference(),
                    outcome.linkedBySubjectId(),
                    outcome.createdAt());
        }
    }
}
