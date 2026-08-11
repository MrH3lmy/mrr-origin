# ADR-0003: Stripe connection and credential-security model

- Status: Accepted
- Date: 2026-08-11

## Context

Phase 3 requires a workspace to authorize its Stripe account before any billing ingestion can start (`ARCHITECTURE.md` Stripe path: "A workspace authorizes a Stripe account"). No connection code exists yet — `apps/api/.../billing` currently contains only `package-info.java`. This ADR must be settled before #10 ("Implement encrypted Stripe account connection") starts, so credentials are never stored under an ad-hoc design.

Constraints already committed elsewhere that this decision must satisfy:

- "Stripe credentials and webhook secrets must be encrypted at rest and must never be logged" (`ARCHITECTURE.md` Security baseline).
- Raw provider input is immutable; derived state is recalculable and replayable (ADR-0002).
- Tenant-owned data is scoped by `workspace_id`; cross-tenant leakage must be structurally prevented, not just tested for (`ARCHITECTURE.md` Tenancy).
- Median setup time target is ten minutes, and MRROrigin supports exactly one billing provider in V1 (`PRODUCT.md`).
- No new infrastructure service before private beta without explicit approval (`AGENTS.md`); a KMS/secrets-manager dependency is treated as configuration of the existing deployment, not a new service, and is called out as a follow-up decision rather than assumed here.
- MRROrigin never moves money or onboards sub-merchants — it only reads a founder's own, already-existing Stripe account.
- Stripe's current documentation marks the OAuth `access_token`/`refresh_token` returned by the Connect OAuth token exchange as deprecated for authenticating API requests against a connected account. The currently supported pattern for a platform acting on a Standard connected account is to use the platform's own secret key together with a `Stripe-Account: <connected_account_id>` header (see References). This materially changes what credential MRROrigin needs to store per workspace, compared to a design that stores and rotates a per-workspace access/refresh token pair.

## Options considered

### Option A — Stripe Connect OAuth for consent, platform secret key + `Stripe-Account` header for API calls (Standard account, `read_only` scope)

The founder clicks "Connect with Stripe," approves a `read_only` scope on Stripe's hosted consent screen, and Stripe redirects back with an authorization code. MRROrigin exchanges the code for the connected account ID (`stripe_user_id`) and the granted scope. The `access_token`/`refresh_token` in that exchange response are not stored or used for API calls, consistent with Stripe's current guidance. Every subsequent API call against that account is made with MRROrigin's own platform secret key, scoped to the connected account via the `Stripe-Account` request header. No secret material is ever seen or copied by the founder.

- **Pros:** matches the "authorizes a Stripe account" UX already assumed in `ARCHITECTURE.md`; the founder never handles a secret string; there is no per-workspace token to encrypt, rotate, or leak — the only Stripe secret MRROrigin holds is a single, centrally managed platform key; this follows Stripe's currently documented integration pattern rather than a token mechanism Stripe is deprecating.
- **Cons:** requires registering and maintaining a Stripe Connect platform integration (OAuth client, redirect handling, account-ID capture); the platform secret key becomes a single high-value credential whose compromise can affect every connected workspace at once, which demands materially stronger operational controls than a per-workspace token would have; detecting founder-initiated revocation can no longer rely on "the stored token stops working" and instead depends on Stripe's `account.application.deauthorized` webhook event plus API-failure fallback detection.

### Option B — Founder-created restricted API key, pasted into MRROrigin

The founder manually creates a restricted key in the Stripe Dashboard, selecting per-resource read/write permissions, and pastes the key into MRROrigin.

- **Pros:** no OAuth integration to build; restricted keys already support fine-grained, resource-level permissions.
- **Cons:** correctness depends entirely on the founder assembling the right permission set by hand — the common failure mode is granting broad or read-write access "to be safe," which is the opposite of least privilege; the key is plaintext the moment it's created, and founders routinely paste such keys into chat or email before reaching MRROrigin, which MRROrigin cannot prevent or detect; revocation requires the founder to remember to delete the key from their Stripe Dashboard rather than a single in-product disconnect action; there is no structured way to bind the key to exactly one Stripe account ID at issuance time, so a copy-paste error can silently connect the wrong account.

### Option C — Full (secret) API key

Rejected outright and not considered further: a secret key has full read/write access to the entire account (balance, payouts, account settings) with no scoping. It fails least privilege by construction and turns any leak into full account takeover. It is included here only to record that it was explicitly considered and rejected, not overlooked.

### Account-type constraint: why Express/Custom Connect accounts are not applicable

Stripe Connect's Express and Custom account types exist for platforms that onboard sub-merchants, handle their KYC/compliance obligations, and move money on the sub-merchant's behalf. MRROrigin does none of this — founders bring an independently created, already-verified Stripe account (a "Standard" account from the platform's perspective). Express/Custom onboarding flows, and the compliance obligations they carry, are not relevant to this decision regardless of which option above is chosen, and MRROrigin must never present itself as a payments platform under Stripe's Connect terms.

## Decision

**Select Option A: Stripe Connect OAuth as the consent/connection flow against the founder's Standard Stripe account, requesting `read_only` scope, followed by API calls authenticated with MRROrigin's platform secret key plus the `Stripe-Account` header — never a stored per-workspace OAuth access or refresh token.**

Rejected alternatives:

- **Storing and rotating per-workspace OAuth access/refresh tokens** (the original shape of Option A) is rejected now that Stripe's own documentation marks those tokens deprecated for authenticating requests. Building rotation machinery around a mechanism Stripe is winding down would add complexity and a false sense of security for no durable benefit.
- **Option B (restricted key paste)** is rejected as the primary, self-serve path for the reasons above (founder-assembled scope, plaintext-in-transit risk, weak revocation story, no structural account-ID binding). It is not built as a UI option in V1. If a specific account cannot complete Stripe's OAuth flow, that is handled as a support-assisted exception, not a general product path, and would need its own follow-up decision if it becomes common.
- **Option C (secret key)** is rejected — it cannot be scoped and is never least privilege.
- **Express/Custom Connect accounts** are rejected as a framing for this problem — MRROrigin is not a payments platform and must not take on the compliance posture those account types imply.

## Least-privilege permissions

- The OAuth consent screen requests only the `read_only` scope. V1 has no product surface that creates, updates, or cancels anything in Stripe, so no write scope is ever requested from the founder.
- The platform secret key used for all `Stripe-Account`-scoped calls is itself a restricted key — not the platform account's full secret key — limited to read-only permissions on the specific resources the billing module normalizes: customers, subscriptions and subscription items, invoices, charges/payment intents, refunds, prices, coupons/discounts, and events, with the Connect "act as connected accounts" capability enabled. Balance, payouts, and Treasury permissions are never granted to this key, even though it can reach any connected account within its granted resources — the key's own restricted permission set is the primary control, and the application-level endpoint allowlist is a second, independent layer.
- If a future feature needs write access (e.g., generating a Billing Portal session link), that is out of scope here and requires a revision to this ADR before implementation, since it changes both the OAuth scope and the platform key's permission set.

## Per-workspace connection record: what is (and isn't) persisted

- Each workspace's Stripe connection persists only non-secret operational metadata: the connected Stripe account ID (`stripe_user_id`), the granted OAuth scope, connection status (`pending` / `active` / `disconnected` / `revoked`), `created_at`/`updated_at`/`last_verified_at` timestamps, and the billing sync checkpoint/cursor used for resumable, idempotent backfill (per `ARCHITECTURE.md` Reliability rules and ADR-0002).
- The OAuth `access_token` and `refresh_token` are never persisted anywhere, for any workspace. Storing them would carry the operational and security cost of a live secret with no functional purpose, since all API calls are made with the platform key and the stored account ID.
- Because this record holds no secret material, it does not need per-row envelope encryption the way a stored token would have — ordinary workspace-scoped database access controls and backups apply, consistent with `ARCHITECTURE.md`'s tenancy rules. It still stores the Stripe account ID, which is treated as a tenant-scoped identifier, not a public value.

## Platform secret key and webhook secrets: centrally managed, not per workspace

- The only Stripe credentials MRROrigin holds are the platform's restricted secret key (used with `Stripe-Account` for every connected account) and the Connect webhook signing secret(s). Both live exclusively in a centrally managed secret store/KMS — never in a workspace or connection database row — satisfying "encrypted at rest and never logged" at the one place the actual secret exists.
- **Blast radius:** because the platform key can act as any connected workspace via the `Stripe-Account` header, its compromise is a full-platform incident affecting every connected workspace simultaneously, not a single-workspace incident the way a per-workspace token compromise would have been. This is the dominant risk in this design and is treated as the highest-value secret in the system.
- **Test/live separation:** Stripe issues distinct test-mode and live-mode secret keys and webhook signing secrets. MRROrigin stores both separately and enforces that a workspace connected in test mode is only ever queried with the test-mode key, and a live-mode connection only with the live-mode key; the two are never interchangeable at runtime.
- **Runtime access restrictions:** only the backend service's runtime identity can unwrap the platform key or webhook secret from the secret store; no admin UI or human operator can view the raw value; unwrap operations are audit-logged by the secret store itself.
- **Redaction:** the platform key and webhook secrets are never written to application logs, error-tracker payloads, or audit trails; audit entries for connect/rotate/disconnect actions capture actor, workspace, timestamp, and action type — never the credential value.
- **Rotation procedure:** create a new restricted key in Stripe with an identical permission set, store it in the secret store alongside the current one, verify a smoke-test `Stripe-Account`-scoped call succeeds against a sample connected account using the new key, switch the application to the new key, then revoke the old key in Stripe. This runs on a fixed schedule and immediately on suspected compromise. The webhook signing secret is rotated by generating a new secret on the Stripe endpoint and verifying incoming signatures against both the current and previous secret during a bounded overlap window, so rotation never causes a gap in webhook processing.

## Webhook ownership and secret handling

- Webhook ownership is at the **platform level**, not per workspace: MRROrigin registers a platform-level Connect webhook endpoint that receives events for every connected Standard account. Sandbox (test-mode) and production (live-mode) use **separate endpoints, each with its own signing secret** — a test-mode event must never be verifiable against the production secret or vice versa.
- Each inbound event carries the originating connected account ID. The ingestion handler looks up the `billing` connection row whose stored Stripe account ID matches, and scopes all further processing to that connection's `workspace_id`. An event whose account ID does not match any known, active connection is stored as an orphaned raw record (per ADR-0002's "dead records remain inspectable" rule) and is never processed into a workspace — this is the structural control against cross-tenant leakage from a webhook routing bug, not just a test case.
- `account.application.deauthorized` is the **primary** out-of-band signal that a founder revoked access from their own Stripe Dashboard. On receipt, the connection is marked `disconnected`/`revoked` immediately, without waiting for a failed API call. An authorization failure on a scheduled API call against a connection is a **secondary** detection path that covers delayed or missed webhook delivery, not the primary mechanism.
- Per `ARCHITECTURE.md`'s reliability rules, the signature is verified against the raw, unmodified request body before any parsing, and the verified raw event is durably persisted (idempotency key = Stripe event ID, unique constraint) before the endpoint acknowledges receipt.

## Disconnect behavior and historical-data retention

- **MRROrigin-initiated disconnect** (a workspace admin clicks Disconnect in-product): MRROrigin explicitly calls Stripe's OAuth deauthorize endpoint to end the authorization Stripe holds for this connected account, then marks the connection `disconnected`. This is done even though there is no long-lived per-workspace token of ours to invalidate — deauthorizing is still the correct way to end the OAuth grant itself.
- **Founder-initiated disconnect from the Stripe Dashboard**: detected via `account.application.deauthorized` (primary) or an authorization failure on the next scheduled call (secondary), and marked `disconnected`/`revoked` accordingly.
- On disconnect, by either path: scheduled backfill/reconciliation jobs for that connection stop immediately, and no further `Stripe-Account`-scoped calls are attempted against that account ID.
- Disconnect never deletes raw immutable Stripe events or the derived customers/subscriptions/MRR movements/attribution history built from them. Per ADR-0002, that data remains the inspectable, replayable record of what happened, and the dashboard continues to show it read-only with a "disconnected — showing data through `<date>`" indicator. Deleting workspace data entirely is a distinct, explicit action tied to the workspace's data-export/deletion flow (`ARCHITECTURE.md` Security baseline; tracked under the Phase 6 readiness issue, #27), not an implicit side effect of disconnecting a billing connection.
- Reconnecting re-runs the OAuth consent flow, captures the (possibly new) account ID and scope, and resumes backfill/reconciliation from the existing checkpoint; the same unique Stripe-object-ID constraints that make backfill idempotent (`ARCHITECTURE.md` Reliability rules) prevent a reconnect from duplicating already-stored history.

## Threats, failure modes, and operational recovery

### Threats considered

1. **Platform secret key compromise** — the dominant threat in this design: because the key can act as any connected account via the `Stripe-Account` header, its exposure affects every connected workspace at once, not one.
2. Webhook signing secret compromise, or forgery/replay of a webhook request.
3. Cross-tenant misrouting — a webhook or scheduled job applying one workspace's Stripe data to another workspace's records because of an account-ID lookup bug.
4. A missed or delayed `account.application.deauthorized` event leaving a revoked connection appearing active until the secondary authorization-failure check catches it.
5. Scope creep — a future code change calling a Stripe endpoint outside the allowlisted read-only resource set, or requesting broader permissions on the platform key than necessary.
6. Founder connects the wrong Stripe account (correct security boundary, wrong tenant intent) — a data-integrity threat rather than a confidentiality one, but with real founder impact.

### Failure modes and recovery

- **Revocation detected via webhook:** `account.application.deauthorized` arrives, the connection is marked `disconnected`/`revoked`, scheduled jobs stop, and the workspace is prompted to reconnect via the Data Health screen (`PRODUCT.md` "integration-health diagnostics").
- **Revocation detected via API failure (fallback path):** a scheduled call against a connection returns an authorization error; the connection is marked unhealthy pending confirmation, polling backs off rather than retrying indefinitely, and the workspace is prompted to reconnect.
- **Webhook signature failures or delivery gaps:** a failed-verification request is rejected and never processed; it is logged without payload or secret content. Stripe's own delivery retries plus the reconciliation job planned for #15 ("Build Stripe reconciliation and billing-data health") are the correctness backstop for any events ultimately missed, consistent with `ARCHITECTURE.md`'s "provider reconciliation and data-health reporting."
- **Secret store/KMS unavailability:** the application fails closed on any operation needing the platform key or webhook secret — it never falls back to an unencrypted or hardcoded path. This is treated as a platform-wide incident, not a per-tenant data-loss event.

### Credential-compromise procedure

Because there is no per-workspace token to individually revoke, a platform-key compromise is treated as a platform-wide incident from the first step, not something that can be contained to one workspace:

1. **Detect** — via secret-store access-audit alerting, Stripe reporting anomalous API usage, or a user/researcher report.
2. **Contain** — immediately rotate the platform secret key in Stripe and update the secret store; this single action is necessary and sufficient to cut off the compromised credential's access across **every** connected workspace at once, since there is no per-workspace token to revoke individually. Roll the webhook signing secret(s) if they are also in scope of the compromise.
3. **Assess exposure across all connected workspaces**, not just one — audit access logs for the compromise window to determine which connected accounts the compromised key was used against and what data (customer, subscription, invoice, payment metadata) could have been read, since even a read-only key exposes PII and payment metadata if misused.
4. **Verify continuity** — confirm the new platform key's smoke test succeeds against a sample of connected accounts before considering ingestion fully restored; no founder action or re-authorization is required unless a specific workspace's account ID/scope is itself suspected compromised.
5. **Notify** affected workspace(s) — potentially all of them, given the shared-key blast radius — and follow incident-response/legal notification obligations based on the exposure assessment.
6. **Close out** — review how the key was exposed (e.g., a logging gap, a dependency issue, an over-broad secret-store grant) before closing the incident, and tighten runtime access controls if the review finds a gap, since a single-key model requires stronger containment discipline than a per-workspace-token model would have.

## Consequences

- Founders get a "Connect with Stripe" action with no secret to copy or paste, matching the ten-minute setup target, and MRROrigin does not build or maintain per-workspace OAuth token storage or rotation machinery around a mechanism Stripe itself is deprecating.
- MRROrigin must build and maintain a Stripe Connect OAuth integration for consent capture (registration, redirect handling, account-ID/scope capture) as part of #10, plus the `Stripe-Account`-header call pattern for all Stripe API access.
- The platform secret key becomes the system's single highest-value Stripe credential; its centrally managed storage, restricted permission set, and rotation/compromise procedures must be in place before any workspace is connected, not added later.
- Detecting a founder-initiated disconnect now depends primarily on reliable delivery of `account.application.deauthorized`, with an API-failure fallback covering delivery gaps — this must be built and tested as a first-class path, not treated as an edge case.
- Test-mode and live-mode keys, webhook endpoints, and signing secrets must be kept separate from day one; conflating them would let a sandbox event or credential affect production data.
- Webhooks are received at platform-level (per-mode) endpoints and demultiplexed by connected-account ID, which simplifies endpoint management but makes the account-ID-to-workspace lookup a security-critical path that must be covered by cross-tenant integration tests, per `ARCHITECTURE.md`'s tenancy rules.
- Disconnect/reconnect must remain idempotent against already-stored history, reinforcing the idempotency rules already required by `ARCHITECTURE.md`.
- Support and on-call procedures must incorporate the platform-wide credential-compromise runbook above before external beta.

## References

- [Stripe Connect OAuth reference](https://docs.stripe.com/connect/oauth-reference)
- [Stripe Connect OAuth for Standard accounts](https://docs.stripe.com/connect/oauth-standard-accounts)
- [Stripe Connect authentication](https://docs.stripe.com/connect/authentication)
- [Stripe Connect webhooks](https://docs.stripe.com/connect/webhooks)
