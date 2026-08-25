# Pre-beta production-like smoke test

## Purpose

Run this checklist against one exact release candidate before inviting the first founder into the
private beta. This verifies that the deployed beta environment can execute the real founder journey
and that the existing observability, recovery, and tenant-isolation controls are usable outside CI.

This smoke-test workspace is operational evidence only. It must **not** count toward issue #29's
recruited, onboarded, engaged, returned, decision-change, or willingness-to-pay totals.

The smoke test does not replace:

- `docs/security/private-beta-checklist.md`;
- `docs/operations/observability-runbook.md`;
- `docs/operations/recovery-runbook.md`;
- `docs/operations/load-readiness.md`; or
- the founder evidence templates under `docs/private-beta/`.

If a check fails because of a concrete repository-owned defect, create a narrow engineering issue
before changing code. Do not reopen #28 or invent a generalized readiness project.

## Preconditions

Do not begin until all of these are true:

- [ ] Record the exact immutable release SHA: `________________`.
- [ ] Record the beta environment/deployment: `________________`.
- [ ] Record API and web base URLs.
- [ ] Record operator, reviewer, and UTC start time.
- [ ] Confirm the deployed artifacts can be mapped back to the recorded SHA.
- [ ] `docs/security/private-beta-checklist.md` is current.
- [ ] Production secret storage/KMS decision is complete for the beta environment.
- [ ] Production identity/OIDC decision is complete for the beta environment.
- [ ] Required GitHub repository-admin security settings are verified.
- [ ] `cd apps/api && mvn verify` passed for the release candidate.
- [ ] `pnpm check` passed for the release candidate.
- [ ] The monitoring backend can scrape the beta environment and operators can access it.
- [ ] The checked-in alert rules and dashboard are available to the beta monitoring system.
- [ ] A dedicated, non-founder smoke-test workspace/project exists or can be created safely.
- [ ] Two disposable test identities are available: identity A belongs to workspace A; identity B
      does not.
- [ ] Test traffic uses non-sensitive identifiers and does not copy real founder/customer data into
      this checklist.

Stop here if any hard precondition is unresolved.

## 1. Health and deployment

- [ ] `GET /actuator/health` reports the expected healthy/ready state.
- [ ] Deployment liveness/readiness probes succeed through the real deployment network path.
- [ ] `GET /actuator/info` exposes no credentials or sensitive configuration.
- [ ] Unapproved Actuator endpoints such as `/actuator/env`, `/actuator/beans`, and
      `/actuator/heapdump` remain unavailable.
- [ ] Restart one application instance using the deployment's normal safe procedure.
- [ ] Health returns after restart and queued durable work remains recoverable.

Record the release SHA, deployment revision, restart timestamp, and observed recovery.

## 2. Authentication and tenancy

Use the production-approved authentication flow; do not use the local token-paste development flow.

- [ ] Open the beta web URL unauthenticated and complete the approved OIDC sign-in as identity A.
- [ ] Verify callback/session behavior according to the accepted production identity decision.
- [ ] Confirm session cookies are HTTP-only, secure, correctly scoped, and use the approved SameSite
      and lifetime policy.
- [ ] Confirm access, refresh, and ID tokens are absent from client-readable browser storage.
- [ ] Verify logout clears the session and a new protected request requires authentication.
- [ ] Verify invalid, expired, wrong-issuer, and wrong-audience credentials are rejected as expected,
      not converted into `500` responses.
- [ ] Confirm identity A can access workspace A.
- [ ] Confirm identity B cannot enumerate or access workspace A.
- [ ] Substitute a project identifier from another workspace and confirm the documented concealed or
      forbidden response with no cross-tenant data leakage.

## 3. Workspace, project, tracker, and ingestion

- [ ] Create or select workspace A and project A through the normal founder path.
- [ ] Configure an allowed domain through the normal UI/API.
- [ ] Obtain the project's public ingestion key through the normal setup path.
- [ ] Install the release-candidate tracker on the smoke-test site.
- [ ] Generate a page view and one safe custom event.
- [ ] Confirm first-party visitor/session identifiers persist according to the tracker contract.
- [ ] Confirm the browser bundle, HTML, network responses, and console output contain no backend,
      Stripe, database, email-provider, or OIDC secret.
- [ ] Send one uniquely identified batch to `POST /api/public/v1/events`.
- [ ] Confirm the event is accepted and visible through the documented diagnostics/data-health path.
- [ ] Resend the same event identifiers and confirm duplicate delivery is idempotent.
- [ ] Confirm a blocked origin is rejected with the documented `4xx` behavior.
- [ ] Confirm an invalid or revoked ingestion key returns `401`.
- [ ] Confirm malformed input returns a client-error response rather than `500`.
- [ ] Safely exercise rate limiting and confirm `429` plus `Retry-After`.

Record accepted, duplicate, rejected, conflict, and rate-limit outcomes without storing request bodies
that may contain sensitive data.

## 4. Deterministic identify and customer linking

- [ ] Call tracker `identify()` with a stable, non-email disposable external user ID.
- [ ] Confirm the visitor is linked to that external user within project A.
- [ ] Repeat the same identify operation and confirm idempotency.
- [ ] Exercise one disposable conflicting identity case and confirm the documented conflict behavior.
- [ ] Confirm no raw email or probabilistic identifier is required.
- [ ] After Stripe data is available, use the documented authenticated server-side customer-linking
      flow to link the intended Stripe customer to the identified application user.
- [ ] Repeat the same link and confirm idempotency.
- [ ] Attempt a cross-workspace link using disposable data and confirm no link is created.

## 5. Stripe connection and backfill

Use a controlled Stripe test-mode account unless live-mode smoke validation has been separately
approved.

- [ ] Start Stripe OAuth through the normal founder UI.
- [ ] Confirm the intended Stripe account and test/live mode before authorization.
- [ ] Confirm the deployed redirect URI matches the configured environment.
- [ ] Confirm OAuth state is time-bounded, single-use, and bound to the initiating workspace/subject.
- [ ] Authorize only the approved read-only scope.
- [ ] Confirm MRROrigin reports the intended connection as active/verified.
- [ ] Confirm no OAuth access/refresh token is exposed in browser-readable storage, logs, or errors.
- [ ] Confirm test-mode operations use only test-mode platform/webhook configuration.
- [ ] Verify cancellation, invalid/replayed state, and wrong-mode scenarios fail safely without
      unexpected `500` responses.
- [ ] Confirm identity B cannot inspect, disconnect, verify, or recover workspace A's connection.

For initial synchronization:

- [ ] Observe `GET /api/workspaces/{workspaceId}/stripe-connection/health`.
- [ ] Record starting backfill phase/checkpoint and timestamp.
- [ ] Confirm progress advances to `DONE` without routine operator intervention.
- [ ] Record elapsed time, warnings, retries, and any intervention.
- [ ] Exercise one safe interrupted/resume path using the recovery runbook and the bounded
      `POST /api/workspaces/{workspaceId}/stripe-connection/backfill/resume?maxPages=25` endpoint.
- [ ] Confirm resume converges without duplicate normalized billing output.

## 6. MRR correctness

Use controlled Stripe fixtures/account state whose expected recurring-revenue behavior is known.

- [ ] Confirm imported supported subscription state produces the expected current MRR snapshot.
- [ ] Check amount, currency, interval normalization, effective timestamp, and movement type.
- [ ] Confirm duplicate/replayed Stripe input does not duplicate MRR movements or snapshots.
- [ ] Confirm unsupported billing shapes remain explicitly unsupported instead of producing plausible
      invented revenue.
- [ ] Confirm a processing failure is observable and recoverable without exposing payment details.

Record only sanitized correctness evidence; do not copy customer, invoice, payment, or webhook payloads.

## 7. Attribution

- [ ] Confirm the controlled acquisition touchpoint predates the eligible new-MRR movement.
- [ ] Confirm deterministic identity/customer linkage exists.
- [ ] Observe automatic attribution recalculation or inspect status at
      `GET /api/workspaces/{workspaceId}/projects/{projectId}/attribution-recalculation`.
- [ ] Confirm the run reaches `COMPLETED`.
- [ ] Verify first-touch and last-touch results against the controlled evidence.
- [ ] Confirm evidence/confidence is inspectable.
- [ ] Confirm a deliberately unlinked customer remains `Unattributed`.
- [ ] Exercise bounded resume where appropriate and confirm deterministic, duplicate-free output.
- [ ] Confirm identity B cannot inspect or mutate workspace A's recalculation state.

Use `docs/operations/recovery-runbook.md` for resume/restart semantics. Do not restart a completed
sweep merely to make the smoke test pass.

## 8. Founder reporting surfaces

Open the same surfaces a founder will use during beta:

- [ ] Overview.
- [ ] Sources.
- [ ] Retention.
- [ ] Customers/evidence timeline.
- [ ] Data health.

For the controlled test data:

- [ ] Confirm MRR and movement values reconcile to the known Stripe state.
- [ ] Confirm source/campaign/landing-page output reconciles to the known tracking evidence.
- [ ] Confirm attribution coverage numerator/denominator and exclusions are understandable.
- [ ] Confirm retained-MRR output is internally consistent where the fixture has enough history.
- [ ] Confirm empty, loading, unsupported, unattributed, degraded, and error states map to real backend
      state rather than optimistic placeholders.
- [ ] Confirm identity B cannot retrieve workspace A reports.
- [ ] Record representative request latency and any unexpected `5xx`.

## 9. Metrics, alerts, and dashboard

Follow `docs/operations/observability-runbook.md` as the source of truth.

### Metrics

- [ ] From the approved monitoring network, scrape `GET /actuator/prometheus`.
- [ ] Confirm the endpoint is not publicly reachable outside the intended deployment boundary.
- [ ] Confirm ingestion, webhook, backfill, MRR, attribution, reporting, and scheduled-delivery metrics
      appear after the corresponding smoke-test activity.
- [ ] Confirm metric labels contain no workspace, project, customer, Stripe object, email, or JWT
      subject identifiers.
- [ ] Save only sanitized scrape/screenshot evidence.

### Alerts

- [ ] Load `docs/observability/alerts.yml` into the beta alerting system.
- [ ] Confirm every rule parses/evaluates successfully.
- [ ] Confirm the operator can see inactive, pending, and firing states.
- [ ] Exercise one safe synthetic/non-paging alert validation path.
- [ ] Confirm alert routing reaches the designated beta incident contact.
- [ ] Record alert-system version, rules revision/checksum, and load timestamp.

### Dashboard

- [ ] Import/provision `docs/observability/dashboard.json`.
- [ ] Confirm beta operators can authenticate to it.
- [ ] Confirm the dashboard is not publicly accessible.
- [ ] Confirm every panel resolves against the beta metrics source.
- [ ] Confirm environment/release identity is visible or recorded alongside the evidence.
- [ ] Confirm no panel exposes tenant identifiers or secrets.

## 10. Automatic backlog progression

- [ ] Capture baseline webhook backlog, oldest-pending age, backfill state, attribution state/cursor, and
      scheduled-delivery state where applicable.
- [ ] Generate controlled work.
- [ ] Observe automatic webhook normalization progressing without manual replay.
- [ ] Observe attribution recalculation progressing at its configured cadence.
- [ ] Confirm checkpoints/cursors advance across at least two observations.
- [ ] Confirm the backlog drains without routine operator intervention.
- [ ] Confirm work in workspace A does not stop unrelated workspace B progress.
- [ ] Record scheduler configuration and elapsed drain time.

A growing backlog that requires repeated manual intervention is a failed beta-environment check even
if the underlying recovery endpoint works.

## 11. Replay and recovery

Use `docs/operations/recovery-runbook.md`; do not improvise repair steps.

- [ ] Exercise a safe transient failed-webhook replay in the non-counting workspace.
- [ ] Record status/failure kind before replay.
- [ ] Confirm replay returns the event to the normal processing path and it converges.
- [ ] Replay again where safe and confirm no duplicate billing/MRR output.
- [ ] Confirm a permanent/unsupported failure is not hot-looped.
- [ ] Exercise interrupted backfill resume.
- [ ] Exercise attribution resume; use restart only for the documented correction case.
- [ ] Compare health/status before and after every recovery action.
- [ ] Escalate instead of retrying indefinitely after repeated identical failures.

## 12. Cross-tenant isolation

For each authenticated endpoint family exercised above:

- [ ] Identity A succeeds against workspace/project A.
- [ ] Identity B cannot access workspace/project A.
- [ ] Substitute a nonexistent workspace identifier and compare the externally visible denial.
- [ ] Substitute a project/customer/event/run identifier belonging to workspace B.
- [ ] Confirm no response, metric label, or caller-visible log reveals another tenant.
- [ ] Confirm ingestion key A cannot write into project B.
- [ ] Confirm Stripe connection/event routing remains bound to the stored workspace.
- [ ] Confirm replay/resume/recalculation for workspace A does not mutate workspace B counts/state.

Any suspected cross-tenant path is S0: stop affected beta activity and follow the security/incident
procedure.

## 13. Secret and sensitive-data exposure review

- [ ] Inspect application/platform logs for secret exposure using safe known markers or secret
      identifiers, never by copying values into the evidence record.
- [ ] Inspect deployment output and configuration views available to ordinary operators.
- [ ] Inspect `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, and representative error
      responses.
- [ ] Inspect browser HTML, JavaScript bundles, network responses, cookies, local storage, and session
      storage.
- [ ] Confirm Stripe secrets, webhook secrets, database credentials, email-provider tokens, OIDC
      client/session secrets, bearer tokens, authorization codes, raw emails, payment details, and
      customer-identifying data are absent.
- [ ] Confirm secret-store access is auditable without logging secret values.

## 14. Expected `4xx` versus unexpected `5xx`

At minimum, verify these failure classes against the application's documented contract:

| Case | Expected behavior |
| --- | --- |
| Missing/invalid/expired user authentication | `401` |
| Authenticated non-member workspace access | Concealed `404` or documented denial |
| Insufficient workspace role | Documented `403`/concealed denial |
| Invalid/revoked ingestion key | `401` |
| Blocked tracker origin | Documented `4xx` |
| Malformed ingestion payload | `400` |
| Duplicate/conflicting identity/session/event input | Idempotent result or documented `409` |
| Rate limit exceeded | `429` with `Retry-After` |
| Invalid/replayed OAuth state | Documented `4xx` |
| Invalid Stripe webhook signature | Documented `4xx` |
| Unsupported billing shape | Explicit unsupported state; not accidental `500` |
| Unknown recovery target | `404` |
| Invalid recovery state | Documented `409`/`4xx` |
| Application defect, database outage, unhandled exception | Unexpected `5xx`; record incident |

- [ ] Confirm expected client/security failures do not masquerade as server incidents.
- [ ] Record every unexpected `5xx` with timestamp, safe symptom, affected step, and incident ID.
- [ ] Open a narrow engineering issue for a reproducible repository-owned failure before changing code.

## Completion record

Record:

- exact release SHA and beta environment;
- UTC end time;
- operator and reviewer;
- all failed checks and explicitly accepted caveats;
- all retries/interventions;
- incident IDs and linked engineering issues;
- sanitized evidence links; and
- final verdict.

Choose exactly one verdict:

- **READY FOR GUIDED BETA** — every required check passed and no unresolved S0/S1 affects the normal
  founder journey.
- **NOT READY** — at least one required gate/check failed or evidence is insufficient.
- **READY WITH EXPLICITLY ACCEPTED CAVEAT** — only non-blocking limitations remain, each documented
  with owner, evidence impact, and acceptance rationale.

Post the verdict and the exact tested release SHA on issue #29 before inviting the first founder. Do
not convert this operational smoke test into beta product evidence.