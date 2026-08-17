# ADR-0007: Weekly summary email provider

- Status: Accepted
- Date: 2026-08-17

## Context

#26 built the `WeeklySummary` DTO plus text/HTML presentation
(`com.mrrorigin.notification.WeeklySummaryService` /
`WeeklySummaryRenderer`) but deliberately sends nothing. #59 must actually
deliver that content by email on a weekly schedule
(`docs/weekly-summary-delivery-plan.md`). No email-sending dependency,
provider account, or credential exists anywhere in this repository today —
`apps/api/pom.xml` has no mail starter or provider SDK, and no
`mrrorigin.notification.*` configuration exists yet. This is the first time
MRROrigin sends email of any kind (no auth/password-reset email, no invite
email — those flows use the external OIDC provider), so this decision also
sets the pattern for any future transactional email in the product.

`ARCHITECTURE.md`'s "do not add ... before private beta" list covers Kafka,
Redis, microservices, a warehouse, or another _billing_ provider — it does
not restrict adding a focused, single-purpose external email API client,
which is the smallest possible integration for this need (one outbound
HTTPS call per delivery attempt, no SDK-mandated infrastructure).

## Options considered

### Option A — Postmark (transactional-only provider)

Pros: purpose-built for transactional (not marketing/bulk) email, which is
exactly this use case; strong deliverability reputation specifically
because it enforces separation from bulk/marketing sending; simple REST API
(`POST https://api.postmarkapp.com/email`, single API-token header) that
fits the same plain-`RestClient` style already used for `StripeBackfillClient`
— no SDK dependency required; per-message delivery webhooks (bounce,
delivery, spam complaint) available if a future issue wants inbound status
beyond what we track ourselves; free/low-volume tier comfortably covers
pre-beta weekly-digest volume (a handful of workspaces × a few recipients ×
weekly).

Cons: sending domain must be verified (SPF/DKIM/Return-Path DNS records)
before first send — a one-time operational step, not ongoing overhead;
smaller ecosystem/mindshare than SES.

### Option B — AWS SES

Pros: lowest marginal cost at scale; natural fit if the rest of the
deployment ever moves onto AWS infrastructure.

Cons: starts in a sandbox mode that only sends to verified addresses until
production access is requested and approved — an extra approval step with
unpredictable timing that blocks even testing real delivery until granted;
reputation/deliverability is the sender's own responsibility to build and
monitor (no built-in message-stream separation from transactional traffic);
heavier operational surface (IAM policy, region selection, configuration
sets for event tracking) for a feature that sends at most a few hundred
emails/week pre-beta. Rejected for v1: the operational weight is
disproportionate to current volume and timeline.

### Option C — Resend

Pros: modern, developer-friendly REST API in the same simple-HTTP-call
shape as Option A; competitive pricing at this volume; good DX for
domain verification.

Cons: newer company with a shorter deliverability/reputation track record
than Postmark at the time of this decision; fewer years of abuse-handling
experience specifically for the transactional-vs-bulk separation that
protects sender reputation. A reasonable alternative if the owner has an
existing preference or account, but not the default recommendation.

### Option D — SendGrid

Pros: mature, high-volume-capable, widely integrated.

Cons: primarily built around combined marketing+transactional sending under
one account, which has a documented history of transactional deliverability
being affected by a sender's marketing-sending reputation on the same
platform; heavier API surface than needed for one templated weekly digest.
Rejected for the same reason as Option B — more platform than this feature
needs, without a corresponding benefit at current volume.

## Decision

**Postmark (Option A)**, accessed via a plain `RestClient` HTTP call, the
same pattern `StripeBackfillClient` already establishes for external
provider integration in this codebase — no vendor SDK dependency added to
`pom.xml`.

Rationale: transactional-only sending is the single best fit for "one
weekly digest per eligible recipient," the deliverability benefit of
provider-enforced stream separation directly matters for founders actually
receiving the summary in their inbox rather than spam, the integration
effort matches the existing `RestClient`-based provider-client convention,
and the free/entry tier is sufficient through private beta. If sustained
volume or cost ever changes this calculus post-beta, revisit as a new ADR —
this decision is not assumed permanent.

Sender and reply-to addresses remain purely operator-configured (per
`docs/weekly-summary-delivery-plan.md` blocking question B6, accepted
2026-08-17, naming convention corrected the same day) — no domain value is
fixed by this ADR or committed to the repo; each deployment supplies its
own via `WEEKLY_SUMMARY_SENDER_ADDRESS` / `WEEKLY_SUMMARY_REPLY_TO_ADDRESS`.
The accepted local-part convention is `weekly-summary@<verified domain>`
(sender) / `support@<verified domain>` (reply-to).

## Provider-neutral interface

Delivery code depends on a small port, not the Postmark client directly, so
a future provider swap or a fake in tests never touches call sites:

```java
package com.mrrorigin.notification;

interface EmailSender {
    EmailSendResult send(EmailMessage message);

    record EmailMessage(
            String toAddress, String fromAddress, String replyToAddress,
            String subject, String textBody, String htmlBody,
            String deliveryId) // weekly_summary_deliveries.id -- carried in Postmark Metadata + a
                                // tracing header for cross-system tracing; see "Delivery guarantee"
    {}

    record EmailSendResult(String providerMessageId) {}

    /**
     * Thrown for any send failure; see the timeout/error-behavior section below for how callers
     * classify {@code permanent}. {@code ambiguous} is true when a network-level failure means we
     * cannot tell whether Postmark ever received/queued the message -- see "Delivery guarantee".
     */
    final class EmailSendException extends RuntimeException {
        EmailSendException(String message, boolean permanent, boolean ambiguous, Throwable cause) { super(message, cause); }
    }
}
```

`PostmarkEmailSender implements EmailSender` is the only class that knows
about Postmark's request/response shape or authentication header. Delivery
retry logic (`docs/weekly-summary-delivery-plan.md` §4) calls `EmailSender`
and classifies the result/exception; it has no Postmark-specific branching.

## Configuration and secrets model

Mirrors `StripeConnectProperties` exactly: `@ConfigurationProperties`,
env-var-backed with safe blank defaults, no credential ever hardcoded or
committed.

```java
@ConfigurationProperties(prefix = "mrrorigin.notification.email")
public record EmailProperties(
        String postmarkServerToken,
        String senderAddress,
        String replyToAddress,
        String messageStream,
        Duration requestTimeout,
        String webBaseUrl) { // builds the opt-out link every email must contain -- accepted B4

    public EmailProperties {
        messageStream = blankToDefault(messageStream, "outbound");
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }
}
```

```yaml
mrrorigin:
  notification:
    email:
      postmark-server-token: ${POSTMARK_SERVER_TOKEN:}
      sender-address: ${WEEKLY_SUMMARY_SENDER_ADDRESS:}
      reply-to-address: ${WEEKLY_SUMMARY_REPLY_TO_ADDRESS:}
      message-stream: ${POSTMARK_MESSAGE_STREAM:outbound}
      request-timeout: ${POSTMARK_REQUEST_TIMEOUT:PT10S}
      web-base-url: ${WEB_APP_BASE_URL:}
```

`POSTMARK_SERVER_TOKEN` is the only secret; it is never logged (matches
`AGENTS.md`: "never log credentials"), never stored in the database, and is
supplied purely through environment/secret-store configuration exactly like
`STRIPE_CONNECT_*_SECRET_KEY`. Distinct test/live Postmark server tokens are
out of scope here — Postmark's stream separation (`message-stream`) plus a
single server token is sufficient at this volume; the Stripe test/live
split exists because Stripe itself models two separate account modes,
which Postmark does not.

## Timeout and error behavior

- `requestTimeout` (default 10s) bounds the outbound HTTP call, via
  `RestClient`'s connect/read timeout configuration — the same defensive
  bound `StripeBackfillClient` applies implicitly through its own
  `RestClient` usage; a hung provider call must never hold a delivery
  attempt (or the claiming lease, per the delivery plan's §4b) open
  indefinitely.
- Any non-2xx response or network failure (`ResourceAccessException`, same
  pattern as `StripeBackfillClient`) is wrapped in `EmailSendException` and
  surfaces to the delivery retry loop as an ordinary attempt failure —
  recorded in `weekly_summary_deliveries.last_error` (sanitized,
  length-bounded), advancing `next_attempt_at` per the backoff schedule
  (delivery plan §4c).
- **Ambiguous vs. definite classification** (corrected, required — see the
  delivery plan's "Delivery guarantee"): a `ResourceAccessException` (the
  request may or may not have reached Postmark — connection reset, read
  timeout, proxy failure) is `ambiguous = true`; a non-2xx HTTP response is
  `ambiguous = false` (Postmark definitely received the request and gave a
  definite answer, success or error). `last_outcome_ambiguous` on the
  delivery row is set accordingly. This never changes retry behavior itself
  (an ambiguous transient failure still follows the normal backoff
  schedule) — it exists purely so the audit trail can distinguish "we know
  this attempt failed" from "we don't know if this attempt actually sent,"
  which is what makes the resulting at-least-once (not exactly-once)
  guarantee honest rather than silently swept under a generic error.
- **Permanent vs. transient classification**: Postmark returns a structured
  `ErrorCode` in its JSON error body. Codes indicating the address itself is
  invalid or has been marked inactive/bounced by Postmark (e.g. `300`
  invalid email request, `406` inactive recipient) are classified
  **permanent** and short-circuit straight to `PERMANENTLY_FAILED` without
  consuming the remaining retry budget — retrying a hard-invalid address
  five times over 24h wastes attempts and cannot succeed. Every other
  failure (5xx, timeout, network error, rate limit `429`) is **transient**
  and follows the normal backoff schedule. This mirrors
  `StripeWebhookFailureKind.classify`'s existing transient/permanent split
  for webhook normalization failures — the same classification shape, a new
  provider-specific mapping.
- Credentials, full request/response bodies, and recipient email addresses
  are never logged; only the delivery id, status, error classification, and
  Postmark's own opaque `ErrorCode` integer are safe to log, matching
  `AGENTS.md`'s "never log ... raw emails" rule. The rendered subject/body
  is also never persisted (delivery plan §6) — only send outcome metadata.

## Test strategy

- **`FakeEmailSender implements EmailSender`** (test-only, `apps/api/src/test`),
  the same shape as any existing test double in this codebase would take —
  records every `EmailMessage` it was called with, and is configurable to
  return success, a transient failure, or a permanent failure per call, so
  delivery-loop tests (retry/backoff/terminal-failure/idempotency) run
  without any network dependency, exactly the role `StripeBackfillClient`
  would play if it had a fake (today its own tests go through
  `MockRestServiceServer` — see below for the equivalent here).
- **`PostmarkEmailSender` itself** is tested with Spring's
  `MockRestServiceServer` (already a transitive test dependency via
  `spring-boot-starter-webmvc-test`/`RestClient` test support, no new
  dependency) or WireMock if request/response fidelity against Postmark's
  actual documented contract needs more precision than
  `MockRestServiceServer` conveniently offers — decided at implementation
  time based on how much of Postmark's response shape the classification
  logic needs to exercise. Either way, no test ever calls the real Postmark
  API; CI has no network dependency on an external email provider and no
  live credential.
- Delivery integration tests (concurrency, duplicate-send, retry exhaustion,
  cross-tenant isolation, timezone/DST) use `FakeEmailSender`, following
  this codebase's existing pattern of testing the DB-backed
  claim/lease/idempotency logic directly (see
  `BillingLedgerConcurrencyAndIsolationIntegrationTests`,
  `StripeWebhookReplayIntegrationTests`) rather than mocking at the HTTP
  layer for every scenario.

## Migration and operational consequences

- New Flyway migrations: `weekly_summary_deliveries` and
  `weekly_summary_opt_outs` (delivery plan §3a/§4a), plus a nullable `email`
  column on `workspace_members` if blocking question B3 resolves as
  proposed. All additive; no existing table is altered destructively.
- New required production configuration: `POSTMARK_SERVER_TOKEN`,
  `WEEKLY_SUMMARY_SENDER_ADDRESS`, `WEEKLY_SUMMARY_REPLY_TO_ADDRESS`
  (blocking question B6 in the delivery plan), and `WEB_APP_BASE_URL`
  (accepted B4 — builds the opt-out link every email must contain). The API
  must continue to start cleanly with all of these blank (matching
  `StripeConnectProperties`'s blank-safe pattern) — the scheduler simply
  skips dispatch and logs a configuration warning once, rather than failing
  startup, so a local/dev environment without Postmark (or without a
  deployed web app URL yet) is not blocked from running the rest of the
  API.
- Operational prerequisite before first production send: verify the sender
  domain with Postmark (SPF/DKIM DNS records) — a one-time setup step
  outside this repository, to be tracked as a deployment task rather than
  code.
- No new infrastructure service (no queue, no cache, no second database) —
  consistent with `ARCHITECTURE.md`'s constraint against adding
  infrastructure before private beta. The scheduler is in-process
  `@Scheduled`, and durability comes entirely from PostgreSQL, matching
  every other background job in this codebase.

## Consequences

- **Delivery is at-least-once, not exactly-once** (corrected, required —
  see the delivery plan's "Delivery guarantee"). Postmark's API has no
  documented request-idempotency key, so an ambiguous network outcome on
  retry can rarely produce a provider-side duplicate; this is an accepted
  tradeoff, traced via the delivery UUID carried in Postmark `Metadata` and
  a custom `X-MRR-Origin-Delivery-Id` header, never claimed as impossible
  in any doc, code comment, or test.
- Adds exactly one new external dependency (Postmark API + one HTTPS
  client call), no SDK, no new infrastructure component.
- `EmailSender` being provider-neutral means a future provider change is a
  new adapter class plus a new ADR, not a rewrite of delivery/retry logic.
- Ties initial deliverability reputation to Postmark's platform; monitoring
  bounce/complaint rates (via Postmark's dashboard, or a future webhook
  ingestion issue) becomes an operational responsibility once real
  recipients exist.
- Requires the sender domain to be verified in DNS before any production
  email can be sent — a deployment-time dependency, not a code dependency,
  but real if it is not already done ahead of #59 shipping.

## References

- `docs/weekly-summary-delivery-plan.md` — the delivery contract this ADR
  supports.
- `docs/weekly-summary-export-plan.md` — #26's frozen content/presentation
  contract that delivery sends unchanged.
- ADR-0003 — the closest existing precedent for "centrally configured
  provider credential, never per-workspace, never logged."
