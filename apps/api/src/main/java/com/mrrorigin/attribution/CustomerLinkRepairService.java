package com.mrrorigin.attribution;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mrrorigin.identity.StripeCustomerLinkingService;
import com.mrrorigin.identity.StripeCustomerLinkingService.LinkOutcome;
import com.mrrorigin.identity.StripeCustomerLinkingService.RepairOutcome;
import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Orchestrates #20's repair workflow end to end: delegates the actual create-or-correct write to
 * {@link StripeCustomerLinkingService#repair} (identity module, owns {@code stripe_customer_links}),
 * records an audit entry when the active-link state actually changed, then triggers bounded
 * recalculation limited to the customer(s) whose derived attribution can have changed as a direct
 * result -- never a project- or workspace-wide sweep.
 *
 * <p><b>{@code UNCHANGED} is a true no-op.</b> When the requested pair is already the active link,
 * {@code identityLinking.repair} writes no row. This method mirrors that: no audit entry, and no
 * call to {@link AttributionApplicationService#recalculate}, which would otherwise rewrite {@code
 * calculated_at} (and, if a model-version change were ever mid-flight, other derived fields) on rows
 * that never changed. The already-current explanations are read back via {@link
 * AttributionApplicationService#explanations} instead, so the response shape stays consistent
 * whether or not anything actually moved.
 *
 * <p>For a real {@code CREATED}/{@code CORRECTED} repair, at most two customers are ever
 * recalculated: the repair's target Stripe customer, and, only when the target identity was
 * reassigned away from a different Stripe customer, that customer (its active link was just
 * superseded, so it reverts to {@code NO_ACTIVE_LINK}). A customer whose <em>identity</em> side was
 * superseded (the target Stripe customer previously pointed at a different identity) needs no
 * separate recalculation of its own: it is the same target customer, already covered by the first
 * recalculation.
 *
 * <p>Lives in {@code attribution} rather than {@code identity} because it depends on {@link
 * AttributionApplicationService}, and {@code identity}'s declared module dependencies
 * (ARCHITECTURE.md) do not include {@code attribution} -- {@code attribution} may depend on {@code
 * identity}, so the dependency runs this direction only.
 */
@Service
public class CustomerLinkRepairService {
    private final StripeCustomerLinkingService identityLinking;
    private final AttributionApplicationService attribution;
    private final CustomerLinkRepairAuditService audit;
    private final WorkspaceContext workspaceContext;

    public CustomerLinkRepairService(
            StripeCustomerLinkingService identityLinking,
            AttributionApplicationService attribution,
            CustomerLinkRepairAuditService audit,
            WorkspaceContext workspaceContext) {
        this.identityLinking = identityLinking;
        this.attribution = attribution;
        this.audit = audit;
        this.workspaceContext = workspaceContext;
    }

    @Transactional
    public RepairResult repair(UUID workspaceId, UUID projectId, String externalUserId, String stripeCustomerId) {
        RepairOutcome outcome = identityLinking.repair(workspaceId, projectId, externalUserId, stripeCustomerId);

        if ("UNCHANGED".equals(outcome.actionType())) {
            // True no-op: no link row was written, so nothing is audited and no derived attribution
            // is recalculated. Returning the already-current explanations (rather than recalculating)
            // keeps a repeated identical repair from rewriting calculated_at / evidence on rows that
            // did not actually change.
            List<CustomerAttributionExplanation> current =
                    attribution.explanations(workspaceId, projectId, stripeCustomerId, AttributionV1Engine.MODEL_VERSION);
            return new RepairResult(outcome, current, null, List.of());
        }

        String displacedCustomerId = null;
        LinkOutcome previousIdentityLink = outcome.previousIdentityLink();
        if (previousIdentityLink != null && !previousIdentityLink.stripeCustomerId().equals(stripeCustomerId)) {
            displacedCustomerId = previousIdentityLink.stripeCustomerId();
        }

        audit.record(
                workspaceId,
                projectId,
                stripeCustomerId,
                displacedCustomerId,
                externalUserId,
                outcome.actionType(),
                outcome.link().id(),
                idOf(previousIdentityLink),
                idOf(outcome.previousCustomerLink()),
                workspaceContext.subjectId());

        List<CustomerAttributionExplanation> targetResult =
                attribution.recalculate(workspaceId, projectId, stripeCustomerId);
        List<CustomerAttributionExplanation> displacedResult = displacedCustomerId == null
                ? List.of()
                : attribution.recalculate(workspaceId, projectId, displacedCustomerId);

        return new RepairResult(outcome, targetResult, displacedCustomerId, displacedResult);
    }

    private static UUID idOf(LinkOutcome link) {
        return link == null ? null : link.id();
    }

    public record RepairResult(
            RepairOutcome linkOutcome,
            List<CustomerAttributionExplanation> targetCustomerAttribution,
            String displacedCustomerId,
            List<CustomerAttributionExplanation> displacedCustomerAttribution) {}
}
