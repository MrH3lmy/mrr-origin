# Security and privacy readiness — threat model and gap inventory

Status as of the #27 (P6 security readiness) audit, last reconciled 2026-08-19 after #62
(workspace deletion), #64 (workspace export), and #65 (public-ingestion rate limiting) all merged.
This document is the source of truth for what is already enforced, what is a known gap, and who
owns triaging new findings. It replaces the need to re-derive this state from code on every
review; update it whenever a listed control changes.

## 1. Authentication and tenant isolation

**Enforced today:**

- The API is an OAuth 2.0 resource server (`SecurityConfiguration`): every route except
  `/actuator/health*`, `/actuator/info`, `/error`, the public event-ingestion endpoint, the Stripe
  OAuth callback, and the Stripe webhook endpoints requires a validated JWT (`anyRequest().authenticated()`).
  Sessions are stateless; CSRF is disabled because there is no cookie-based session to forge.
- `WorkspaceContext.requireMembership`/`requireManager` re-check the caller's `workspace_members`
  row on every request from the validated JWT `sub` claim — client-supplied workspace IDs or roles
  are never trusted (`docs/local-authentication.md`). A non-member gets `404 Not Found`, never `403`,
  so workspace existence is not leaked to outsiders.
- Role model is `OWNER > ADMIN > MEMBER > VIEWER` (`WorkspaceRole`); manager-only mutations use
  `requireManager`, and read paths that must additionally redact sensitive fields for non-managers
  (e.g. customer-identity CSV export) use the non-throwing `canManage` check.
- Cross-tenant behavior is integration-tested per module, not just asserted once centrally:
  `WorkspaceManagementIntegrationTests#crossWorkspaceAccessIsHiddenAndDenied`,
  `BillingLedgerStructuralIsolationIntegrationTests`,
  `BillingLedgerConcurrencyAndIsolationIntegrationTests`,
  `CsvExportIntegrationTests#customersExportRedactsIdentityForNonManagersButNotForOwners`, and
  equivalents in `attribution`, `reporting`, and `tracking`.

**Gaps / follow-ups:**

- Production identity provider is an explicit deferred architecture decision
  (`ARCHITECTURE.md` → Deferred decisions). Nothing here is blocked on it, but the private-beta
  checklist (§ below) tracks it as a hard gate.
- No session/BFF topology decision yet for the web app's browser-facing auth flow (also deferred).

## 2. Public ingestion endpoints

**Enforced today (`EventIngestionController`, `/api/public/v1/events`):**

- Requests must present a valid, active per-project ingestion key (`X-Ingestion-Key`); an invalid
  or revoked key is rejected before any origin/body work happens.
- Origin is normalized and checked against the project's allowed-domain list
  (`AllowedDomainService`); a missing/invalid `Origin` header or a disallowed origin is rejected.
- Request bodies are capped (`IngestionBodyLimitFilter`, 1 MiB) independent of any client-declared
  `Content-Length`.
- Requests that pass key resolution and the allowed-origin check are rate-limited per ingestion key
  by `IngestionRateLimiter`: a DB-backed, multi-instance-safe fixed one-minute window counter using
  atomic PostgreSQL `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`. The threshold is operator-
  configurable through `mrrorigin.tracking.rate-limit.requests-per-minute` /
  `INGESTION_RATE_LIMIT_PER_MINUTE` and defaults to 60 requests/minute. Over-limit requests return
  `429 Too Many Requests` with `Retry-After`. The counter runs in its own transaction so a rejected
  or later-failing admitted request still consumes budget; no correctness-critical counter state
  lives only in process memory. `IngestionRateLimitIntegrationTests` covers configured limits,
  `Retry-After`, key rotation/isolation on the same project/workspace, window reset, and concurrent
  bursts without lost increments.
- Rate limiting intentionally runs after the origin allow-list check: wrong-origin traffic therefore
  cannot consume a legitimate integration's budget or use `429` as an oracle for that integration's
  current traffic level. Rate-limited attempts are not written to `tracking_ingestion_failures`;
  that table remains a misconfiguration diagnostic rather than a high-volume throttle log.
- Blocked-origin attempts are always recorded for the data-health diagnostics screen
  (`TrackingIngestionFailureRecorder`). Invalid-key and invalid-payload attempts are recorded
  best-effort, only where the request can be attributed to a project: an invalid key is recorded
  only when `resolveProjectByPrefixForDiagnostics` can still resolve its prefix to a project, and an
  invalid payload only when the key on the request fully resolves. This is a deliberate
  privacy-preserving tradeoff (no project attribution is invented for a key that doesn't resolve at
  all), not an oversight — see `EventIngestionController`.

**Known trade-offs / follow-ups:**

- The V1 limiter uses fixed one-minute windows rather than a sliding window or token bucket. A burst
  straddling a minute boundary can therefore admit up to roughly twice the configured rate across a
  very short interval. This is an accepted V1 precision trade-off; move to a sliding/token-bucket
  algorithm only if production traffic shows the boundary behavior is too permissive.

## 3. Stripe secrets and webhooks

**Enforced today**, per [ADR-0003](../adr/0003-stripe-connection-credential-security.md):

- No per-workspace Stripe token is ever stored. Workspaces are represented only by the connected
  account ID plus non-secret status metadata (`StripeConnection`); all API calls are made with a
  single centrally managed, restricted platform secret key plus the `Stripe-Account` header.
- Webhook signatures are verified against the exact raw request bytes before any parsing
  (`StripeWebhookController`), the body is read with a hard 1 MiB cap enforced against actual bytes
  streamed (not a declared `Content-Length`), and events are durably persisted (idempotency key =
  Stripe event ID) before acknowledgement.
- Test-mode and live-mode use separate endpoints (`/api/stripe/webhooks/{test|live}`) and separate
  signing secrets; an unrecognized mode segment 404s rather than falling back to a default.
- An inbound event whose Stripe account ID matches no known, active connection is stored as an
  orphaned raw record and never applied to any workspace — this is the structural control against
  cross-tenant misrouting, and it is integration-tested
  (`StripeWebhookIngestionIntegrationTests#unknownAccountEventIsAcknowledgedAndStoredAsOrphaned`,
  `#disconnectedAccountEventIsAcknowledgedAndStoredAsOrphaned`).
- `account.application.deauthorized` is the primary revocation-detection signal; a failed
  authorization on a scheduled API call is the secondary fallback.

**Gaps / follow-ups:**

- The platform secret key, Connect webhook secrets, DB credentials, and the Postmark token are all
  currently sourced from process environment variables (`application.yml`), not a dedicated
  KMS/secrets manager. ADR-0003 already flags KMS selection as a deferred follow-up decision, not an
  oversight — no infrastructure service should be added before private beta without an ADR
  (`AGENTS.md`). Tracked as a private-beta gate, not fixed in this PR.
- Rotation _procedure_ for the platform key/webhook secret is documented narratively inside
  ADR-0003 §"Rotation procedure" and §"Credential-compromise procedure" but wasn't collected into an
  operator-facing runbook alongside the other secrets in this system. Fixed in this PR:
  `docs/security/rotation-runbook.md`.

## 4. Exports, logs, and operator access

**Enforced today:**

- All three v1 CSV exports (`CsvExportController`) are workspace-membership-checked, streamed (not
  buffered into memory), carry a stable `X-Export-Schema-Version` header, and are audited after the
  write completes (`ExportAuditService`: actor subject, filters, row count). The customers export
  additionally redacts identity fields for non-managers, tested above.
- A repo-wide grep for log statements that include email/token/secret/key/password payloads found
  none in `apps/api/src/main`; the only two `log.warn` call sites touching those terms
  (`WorkspaceMemberEmailCaptureService`, `WeeklySummaryDispatchService`) log a workspace ID and a
  static "not configured" message respectively, never a credential or address value.
- Actuator surface is deliberately minimal (`management.endpoints.web.exposure.include:
  health,info,prometheus` in `application.yml`, as of #90/P6 observability) — no `env`, `beans`,
  `httptrace`, or `heapdump` endpoints are exposed. `/actuator/prometheus` (Micrometer +
  `micrometer-registry-prometheus`, #90) is `permitAll` in `SecurityConfiguration`, the same posture
  as `/actuator/health*`/`/actuator/info` and for the same reason: a Prometheus-compatible scraper is
  not a workspace member and this system has no operator/service-account JWT identity to give it (see
  the next section). No hardcoded monitoring secret was introduced to work around this; the
  access-control boundary for this endpoint in production is deployment-level network isolation,
  documented but not enforced in code. Every custom `mrrorigin.*` meter this endpoint exposes is
  tagged only with small fixed enums (result/outcome/reason/mode/status/failure_kind) and aggregated
  across every tenant — never a workspace/project/customer/Stripe-object id, email, or JWT subject
  (enforced by a regression test). See `docs/operations/observability-runbook.md` for the full metric
  catalog, alert rules, and dashboard definition.

**Gaps / follow-ups:**

- **There is no operator/admin/support surface in the application at all.** Every controller in
  `com.mrrorigin` is workspace-member-scoped; there is no platform-operator role, no admin API, no
  "view any workspace" tooling. Today, "operator access" means direct infrastructure/database access,
  which is outside this codebase's authorization model and not something an ADR here can fully
  control. This is fine as a _pre-beta_ posture (smaller surface, nothing to lock down that doesn't
  already exist) but must be explicit: the private-beta checklist requires that infra/DB access be
  restricted and audited at the infrastructure layer, and that any future support-tooling surface be
  scoped as its own issue with audit logging designed in from the start, not bolted on.
- No structured audit log exists yet for administrative actions outside of exports (e.g. workspace
  member role changes, Stripe connect/disconnect). `WorkspaceManagementService` and
  `StripeConnectionService` changes are visible in normal application data (row history) but not in
  a dedicated audit trail the way exports and customer-link repairs (`CustomerLinkRepairAuditService`)
  are. Not fixed in this PR; noted for a future slice if/when it becomes a real support-tooling need.
- **`/actuator/prometheus` (#90) is `permitAll` with no application-level authentication**, relying
  entirely on deployment-level network isolation (the scrape target must not be reachable from the
  public internet) as its access-control boundary. This is the same category of gap as "operator
  access... outside this codebase's authorization model" above, and is tracked on the private-beta
  infrastructure checklist alongside it, not fixed in application code -- there is no
  operator/service-account identity in this system's JWT-based auth model to authenticate a scraper
  against, and this PR does not introduce a hardcoded shared secret to work around that.

## 5. Workspace data export and deletion

**Enforced today:**

- Project-scoped **tracking** data deletion exists end to end (`ProjectDataDeletionController`,
  `ProjectDataDeletionService`, `ProjectDataDeletionPhase`): resumable, batched, manager-authorized,
  integration-tested (`ProjectDataDeletionIntegrationTests`). This is Phase 2 (#8) scope, not a
  workspace-wide flow.
- The three reporting CSV exports (§4) let a workspace export specific _views_ of its own data, but
  they are not a full data bundle and don't cover billing/revenue/attribution raw records.
- **Workspace-wide export** (`GET /api/workspaces/{workspaceId}/exports/data`, PR #78, closes #64,
  [ADR-0009](../adr/0009-workspace-data-export.md)): manager-only, a synchronously streamed ZIP
  containing a versioned `manifest.json` plus one streaming NDJSON file per workspace-owned domain
  (billing, revenue, attribution, reporting, notification, tracking). Every query uses an explicit
  column allow-list rather than `SELECT *`, so new columns are excluded by default until reviewed;
  credentials, secret digests, lease tokens, OAuth CSRF state, and Stripe webhook raw signed bytes
  are excluded (the parsed webhook payload is included, matching #64's accepted scope). Rows stream
  through `ZipOutputStream` with bounded keyset pagination (500 rows/page) — never buffered fully in
  memory. Successful exports are audited in `workspace_export_audit_log` (actor subject, schema
  version, per-domain row counts, timestamp; never row content). Cross-tenant access is hidden as
  `404`, matching the rest of the API. `WorkspaceDataExportIntegrationTests` (9 tests) covers
  manager authorization, cross-tenant denial, manifest/row-count correctness, the secret-exclusion
  regression, audit-without-content-leakage, and pagination past the 500-row boundary.
- **Workspace-wide deletion** (`POST /api/workspaces/{workspaceId}/deletion`, PR #76, closes #62,
  [ADR-0008](../adr/0008-workspace-deletion-lifecycle.md)): owner-only, requires the exact
  confirmation string `DELETE <workspace slug>`; a retry with the same confirmation returns the
  existing request rather than starting duplicate work. Admission runs synchronously: marks the
  workspace `DELETING` (after which `WorkspaceContext.requireManager`/`requireWritable` reject
  further mutations with `409`), revokes every project ingestion key, and disables Stripe sync
  locally. A new `workspacelifecycle` module (the one deliberate exception to the module dependency
  order, alongside `workspaceexport`; see `ARCHITECTURE.md` → Backend modules) then orchestrates a
  resumable, checkpointed, leaf-first hard delete: notification → reporting → attribution → revenue
  → identity → tracking → billing → workspace root, mirroring `ARCHITECTURE.md`'s module dependency
  graph. No Stripe invoice/event payload is retained — `stripe_webhook_events` is hard-deleted
  rather than orphaned. Only a minimal, non-PII **deletion tombstone** survives for 30 days: request
  ID, workspace UUID, status, and timestamps — the table has no column capable of holding PII or
  billing data. `WorkspaceDeletionIntegrationTests` (13 tests) and
  `WorkspaceDeletionCompletionAndIsolationIntegrationTests` (2 tests) cover owner-only authorization,
  confirmation mismatch, duplicate/concurrent requests, retry-after-completion via the tombstone
  fallback, crash/resume across bounded batches, write rejection once `DELETING`, leaf-first phase
  ordering, ingestion-key/Stripe-sync admission effects, full dependency-safe deletion across every
  module's owned tables, and cross-tenant concealment. All 24 tests across both features re-ran
  clean on 2026-08-19 as part of the #27 close-out audit.

**Known trade-off:**

- Workspace deletion disables Stripe sync **locally only** — it does not call Stripe to revoke the
  OAuth grant itself. This is a deliberate ADR-0008 scope decision so admission stays a durable,
  no-external-network-dependency step. Revoking the Stripe OAuth grant is a non-blocking follow-up,
  not tracked as a private-beta gate.

## 6. Secret and key rotation

**Enforced today:**

- Project ingestion keys are self-serve rotatable by workspace managers
  (`TrackingIngestionKeyController`): issuing a new key immediately revokes the prior secret, and
  the raw secret is returned exactly once, at issue/rotation time, never echoed back afterward.
- The Stripe platform key and webhook-secret rotation _procedure_ is fully specified in ADR-0003
  (§"Rotation procedure", §"Credential-compromise procedure"), including the dual-secret overlap
  window for webhook signature verification so rotation never causes a processing gap.

**Gaps / follow-ups:**

- No single operator-facing runbook previously existed collecting rotation steps for _every_
  secret class in the system (Stripe platform key/webhook secrets, OIDC signing keys, database
  credentials, the Postmark token, ingestion keys). Fixed in this PR: `docs/security/rotation-runbook.md`.
- Rotation is a manual, documented procedure everywhere — there is no automated rotation tooling
  (expected pre-KMS; automating rotation is a natural follow-up once a KMS/secrets manager is
  selected, not before).

## 7. Dependency, secret, and static-analysis tooling

**Gap before this PR:** none of dependency scanning, secret scanning, or static analysis ran in CI.
No `dependabot.yml`/Renovate config, no CodeQL or equivalent SAST workflow, no secret-scanning CI
job. `.github/CODEOWNERS` names a single owner (`@MrH3lmy`) for the whole repository, which doubles
as the de facto triage owner until the team grows.

**Fixed in this PR:**

| Tool                                       | Mechanism                                                                                                          | Scope                                                   | Triage owner                                                                                                                                                     |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Dependency updates/alerts                  | `.github/dependabot.yml` (Maven, npm/pnpm, GitHub Actions ecosystems) + GitHub's native Dependabot security alerts | All three ecosystems, weekly                            | `@MrH3lmy` (per `CODEOWNERS`); triage a Dependabot PR/alert within 5 business days for high/critical, 30 days for moderate/low                                   |
| Static analysis (SAST)                     | `.github/workflows/codeql.yml` (CodeQL for `java-kotlin` and `javascript-typescript`)                              | Every PR, push to `main`, weekly scheduled scan         | `@MrH3lmy`; triage new code-scanning alerts within 5 business days                                                                                               |
| Secret scanning                            | `.github/workflows/secret-scan.yml` (gitleaks)                                                                     | Every PR, push to `main`, full git history on first run | `@MrH3lmy`; any true positive is treated as an incident — rotate the credential immediately per `docs/security/rotation-runbook.md`, then remove it from history |
| Dependency vulnerability check (fast path) | `pnpm audit --audit-level=high` added to the existing `javascript` CI job                                          | Every PR, push to `main`                                | `@MrH3lmy`                                                                                                                                                       |

**Not code-configurable — requires a repository admin action, tracked on the private-beta checklist:**

- Enabling GitHub's native secret-scanning **push protection** (Settings → Code security).
- Enabling GitHub's native **Dependabot alerts** and **Dependabot security updates** toggles
  (`dependabot.yml` alone only configures version-update PRs, not the alert feed).
- Enabling **CodeQL default setup** is not needed since this PR adds an explicit workflow instead;
  don't enable both, they'll duplicate scans.

An OWASP Dependency-Check Maven plugin was intentionally **not** added: it requires either an NVD
API key or a warm local CVE-database cache to avoid slow/flaky CI runs, and Dependabot's native
advisory feed already covers the same Maven-ecosystem CVEs without that operational cost. Revisit if
Dependabot coverage proves insufficient.
