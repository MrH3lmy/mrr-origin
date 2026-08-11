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

## Options considered

### Option A — Stripe Connect OAuth against the founder's existing (Standard) Stripe account, `read_only` scope

The founder clicks "Connect with Stripe," approves a read-only scope on Stripe's hosted consent screen, and Stripe redirects back with an authorization code that MRROrigin exchanges for an access token, a refresh token, and the connected account's Stripe account ID. No secret material is ever seen or copied by the founder.

- **Pros:** matches the "authorizes a Stripe account" UX already assumed in `ARCHITECTURE.md`; the founder never handles a secret string (no risk of it being pasted into Slack, a support ticket, or a screenshot); revocation is self-service from the founder's own Stripe Dashboard at any time, independent of MRROrigin; scope is enforced by Stripe itself at the token level, not just by application code; one connection maps cleanly to one Stripe account ID for tenant scoping.
- **Cons:** requires registering and maintaining a Stripe Connect platform integration (OAuth client, redirect handling, token exchange) before any billing ingestion can be built; slightly more upfront engineering than accepting a pasted key.

### Option B — Founder-created restricted API key, pasted into MRROrigin

The founder manually creates a restricted key in the Stripe Dashboard, selecting per-resource read/write permissions, and pastes the key into MRROrigin.

- **Pros:** no OAuth integration to build; restricted keys already support fine-grained, resource-level permissions.
- **Cons:** correctness depends entirely on the founder assembling the right permission set by hand — the common failure mode is granting broad or read-write access "to be safe," which is the opposite of least privilege; the key is plaintext the moment it's created, and founders routinely paste such keys into chat or email before reaching MRROrigin, which MRROrigin cannot prevent or detect; revocation requires the founder to remember to delete the key from their Stripe Dashboard rather than a single in-product disconnect action; there is no structured way to bind the key to exactly one Stripe account ID at issuance time, so a copy-paste error can silently connect the wrong account.

### Option C — Full (secret) API key

Rejected outright and not considered further: a secret key has full read/write access to the entire account (balance, payouts, account settings) with no scoping. It fails least privilege by construction and turns any leak into full account takeover. It is included here only to record that it was explicitly considered and rejected, not overlooked.

### Account-type constraint: why Express/Custom Connect accounts are not applicable

Stripe Connect's Express and Custom account types exist for platforms that onboard sub-merchants, handle their KYC/compliance obligations, and move money on the sub-merchant's behalf. MRROrigin does none of this — founders bring an independently created, already-verified Stripe account (a "Standard" account from the platform's perspective). Express/Custom onboarding flows, and the compliance obligations they carry, are not relevant to this decision regardless of which option above is chosen, and MRROrigin must never present itself as a payments platform under Stripe's Connect terms.

## Decision

**Select Option A: Stripe Connect OAuth against the founder's Standard Stripe account, requesting `read_only` scope.**

Rejected alternatives:

- **Option B (restricted key paste)** is rejected as the primary, self-serve path for the reasons above (founder-assembled scope, plaintext-in-transit risk, weak revocation story, no structural account-ID binding). It is not built as a UI option in V1. If a specific account cannot complete Stripe's OAuth flow, that is handled as a support-assisted exception, not a general product path, and would need its own follow-up decision if it becomes common.
- **Option C (secret key)** is rejected — it cannot be scoped and is never least privilege.
- **Express/Custom Connect accounts** are rejected as a framing for this problem — MRROrigin is not a payments platform and must not take on the compliance posture those account types imply.

## Least-privilege permissions

- MRROrigin requests only the `read_only` OAuth scope. V1 has no product surface that creates, updates, or cancels anything in Stripe, so no write scope is requested.
- The application-level allowlist of endpoints it will call is limited to what the billing module normalizes: customers, subscriptions and subscription items, invoices, charges/payment intents, refunds, prices, coupons/discounts, and events (for webhook-driven and reconciliation reads). Balance, payouts, and Treasury endpoints are never called, even though a broader scope grant from Stripe could technically permit it — the code path itself is the second layer of least privilege, independent of what OAuth grants.
- If a future feature needs write access (e.g., generating a Billing Portal session link), that is out of scope here and requires a revision to this ADR before implementation, since it changes the credential's blast radius.

## Encryption at rest, key management, rotation, redaction

- **Storage:** the OAuth access token, refresh token, and the platform's webhook signing secret are encrypted at the application layer with envelope encryption (AES-256-GCM) before being written to PostgreSQL — never relying on disk/volume encryption alone to satisfy the "encrypted at rest" baseline. Each credential record has its own generated data key, which is itself encrypted ("wrapped") by a master key held in a managed KMS/secrets manager. The specific KMS provider is an implementation detail for #10, not this ADR, but "encrypt directly with a static key baked into application config" is explicitly rejected as insufficient.
- **Key management:** the KMS master key is never present in application config or environment variables in plaintext — only a reference (key ID/ARN) is. Access to unwrap operations is restricted to the backend service's runtime identity and is audit-logged by the KMS itself. Staging and production use separate master keys so a staging compromise cannot decrypt production credentials.
- **Rotation** has two independent axes:
  - _Encryption-key rotation_ (the KMS master key): rotated on the KMS provider's native schedule; rotating the master key re-wraps data keys lazily on next access plus a background sweep, and never requires re-encrypting Stripe-side secrets.
  - _Credential rotation_ (the Stripe-issued token itself): the refresh token is periodically exchanged for a new access token, and the previous access token is explicitly revoked once the new one is confirmed working, rather than left to linger. This is done on a fixed schedule and immediately on suspected compromise (see the compromise procedure below). The platform-level webhook signing secret is rotated by generating a new secret on the Stripe endpoint and verifying incoming signatures against both the current and previous secret during a bounded overlap window, so rotation never causes a gap in webhook processing.
- **Redaction:** raw token and secret values are never written to application logs, error-tracker payloads, or audit trails. Audit records for connect/rotate/disconnect actions capture actor, workspace, timestamp, and action type — never the credential value. There is no admin "view credential" affordance anywhere in the system; the only operations exposed are rotate and revoke. Database backups and exports contain only ciphertext, consistent with the "never log credentials" rule in `AGENTS.md`.

## Webhook ownership and secret handling

- Webhook ownership is at the **platform level**, not per workspace: MRROrigin registers one Connect webhook endpoint that receives events for every connected Standard account, signed with a single platform signing secret. This avoids provisioning and rotating N per-workspace endpoints and secrets, and matches Stripe's own Connect webhook model.
- Each inbound event carries the originating connected account ID. The ingestion handler looks up the `billing` connection row whose stored Stripe account ID matches, and scopes all further processing to that connection's `workspace_id`. An event whose account ID does not match any known, active connection is stored as an orphaned raw record (per ADR-0002's "dead records remain inspectable" rule) and is never processed into a workspace — this is the structural control against cross-tenant leakage from a webhook routing bug, not just a test case.
- Per `ARCHITECTURE.md`'s reliability rules, the signature is verified against the raw, unmodified request body before any parsing, and the verified raw event is durably persisted (idempotency key = Stripe event ID, unique constraint) before the endpoint acknowledges receipt.
- The webhook signing secret is stored using the same envelope-encryption path described above, scoped as platform/application configuration rather than a per-workspace record.

## Disconnect behavior and historical-data retention

- Disconnect can be initiated in-product by a workspace admin, or happen out-of-band when the founder revokes access from their own Stripe Dashboard. Because the latter is outside MRROrigin's control, the connection's health check must independently detect revocation (a failed API call with an authorization error) and mark the connection `disconnected`, not assume disconnect only happens through MRROrigin's UI.
- On disconnect (either path): the connection is marked `disconnected` immediately, scheduled backfill/reconciliation jobs for it stop, and the cached access/refresh tokens are treated as void. MRROrigin does **not** call Stripe's deauthorize endpoint on a founder-initiated Dashboard revocation (Stripe already invalidated it), but does call it when MRROrigin itself initiates the disconnect, so the token is invalid on both sides immediately.
- Disconnect never deletes raw immutable Stripe events or the derived customers/subscriptions/MRR movements/attribution history built from them. Per ADR-0002, that data remains the inspectable, replayable record of what happened, and the dashboard continues to show it read-only with a "disconnected — showing data through `<date>`" indicator. Deleting workspace data entirely is a distinct, explicit action tied to the workspace's data-export/deletion flow (`ARCHITECTURE.md` Security baseline; tracked under the Phase 6 readiness issue, #27), not an implicit side effect of disconnecting a billing connection.
- Reconnecting re-runs the OAuth flow and resumes backfill/reconciliation from the existing checkpoint; the same unique Stripe-object-ID constraints that make backfill idempotent (`ARCHITECTURE.md` Reliability rules) prevent a reconnect from duplicating already-stored history.

## Threats, failure modes, and operational recovery

### Threats considered

1. Credential exposure via database/backup compromise or insider access.
2. Credential exposure via logging or error-tracking (accidental plaintext capture).
3. Webhook forgery (unsigned or badly signed request) or replay of a previously valid signed event.
4. Cross-tenant misrouting — a webhook or scheduled job applying one workspace's Stripe data to another workspace's records because of a lookup bug.
5. Scope creep — a future code change calling a Stripe endpoint outside the allowlisted read-only resource set.
6. Founder connects the wrong Stripe account (correct security boundary, wrong tenant intent) — a data-integrity threat rather than a confidentiality one, but with real founder impact.

### Failure modes and recovery

- **Token expired/revoked:** detected on the next failed API call. The connection is marked unhealthy and surfaced on the Data Health screen (`PRODUCT.md` "integration-health diagnostics"); scheduled polling backs off rather than retrying indefinitely; the workspace is prompted to reconnect.
- **Webhook signature failures or delivery gaps:** a failed-verification request is rejected and never processed; it is logged without payload or secret content. Stripe's own delivery retries plus the reconciliation job planned for #15 ("Build Stripe reconciliation and billing-data health") are the correctness backstop for any events that are ultimately missed, consistent with `ARCHITECTURE.md`'s "provider reconciliation and data-health reporting."
- **KMS/master-key unavailability:** the application fails closed on any operation needing to decrypt a Stripe credential — it never falls back to an unencrypted path. This is treated as a platform incident, not a per-tenant data-loss event.

### Credential-compromise procedure

1. **Detect** — via KMS/database access-audit alerting, anomalous Stripe API usage reported by Stripe, or a user/researcher report.
2. **Contain** — revoke the affected OAuth token(s) via Stripe's deauthorize endpoint and mark the connection `disconnected` in the database immediately; do not wait on founder action.
3. **Rotate** — roll the platform webhook signing secret if it is in scope of the compromise, and rotate the KMS data-encryption keys protecting the affected record(s), re-encrypting under new keys.
4. **Re-establish** — require a fresh OAuth authorization before ingestion resumes for the affected workspace(s).
5. **Assess exposure** — audit access logs for the compromise window to determine what Stripe data (customer, subscription, invoice, payment metadata) the credential could have read, since even a read-only token exposes PII and payment metadata if misused.
6. **Notify** — inform the affected workspace(s) and follow incident-response/legal notification obligations based on the exposure assessment.
7. **Close out** — rotate the KMS master key itself if key material was in scope, and record how the exposure occurred (e.g., a logging gap, a dependency issue) before closing the incident, so the same failure mode is fixed at its source rather than only remediated for this instance.

## Consequences

- Founders get a "Connect with Stripe" action with no secret to copy or paste, matching the ten-minute setup target.
- MRROrigin must build and maintain a Stripe Connect OAuth integration (registration, redirect handling, token exchange, refresh) as part of #10, in addition to the credential-storage work.
- A KMS/secrets-manager dependency for envelope encryption is required before credentials can be stored; selecting the specific provider is left to #10's implementation and does not need a further ADR unless it introduces a new infrastructure category.
- Webhooks are received at one platform-level endpoint and demultiplexed by connected-account ID, which simplifies endpoint management but makes the account-ID-to-workspace lookup a security-critical path that must be covered by cross-tenant integration tests, per `ARCHITECTURE.md`'s tenancy rules.
- Disconnect/reconnect must remain idempotent against already-stored history, reinforcing the idempotency rules already required by `ARCHITECTURE.md`.
- Support and on-call procedures must incorporate the credential-compromise runbook above before external beta.
