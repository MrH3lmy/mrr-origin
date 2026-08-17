# Weekly summary delivery (#59) — contract-first plan

## Status

**PROPOSED — pending owner review.** This document, plus
[ADR-0007](adr/0007-weekly-summary-email-provider.md), must be reviewed and
the open questions in "Blocking questions" resolved by the issue owner
before implementation code is written, per `CLAUDE.md`'s task instructions
and the precedent set by `docs/weekly-summary-export-plan.md` (frozen only
after owner review). No delivery/scheduling/retry/opt-out code has been
written yet.

## What this reuses (no new calculation or presentation rule)

- **Summary content**: `WeeklySummaryService#summary(workspaceId, projectId,
weekStart)` (#26, `com.mrrorigin.notification`) — unchanged. Delivery
  calls it with `weekStart = null` (last completed week) exactly as the
  scheduler's own trigger time implies.
- **Presentation**: `WeeklySummaryRenderer.renderText` /
  `.renderHtml` (#26) — unchanged; the email body is these renderers'
  output, not a new template.
- **Tenancy/ownership**: `Project.workspaceId`, `ProjectRepository`,
  `WorkspaceMemberRepository` (`workspace` module) for iterating projects
  and resolving recipients. No new tenancy primitive.
- **Lease/claim pattern**: `StripeWebhookNormalizationService`'s
  claim-then-work-then-fenced-apply pattern (`SELECT ... FOR UPDATE SKIP
LOCKED` + lease timestamp stamped in the same statement, fenced
  apply/fail by the exact claimed lease value) — reused verbatim for
  delivery-attempt claiming. `StripeWebhookReplayService`'s batch `FOR
UPDATE SKIP LOCKED` CTE shape is reused for the retry sweep.
- **Audit pattern**: `export_audit_log` (V18) / `stripe_customer_link_repair_audit_log`
  (V17) — append-only, no row-level/content data, `(project_id,
workspace_id) → projects ON DELETE CASCADE`. Reused for
  `weekly_summary_deliveries`.
- **Project-scoped settings pattern**: `project_tracking_retention_settings`
  (V15) — one row per project, absent row means default — reused for opt-out
  storage shape (see below).
- **Config/secrets pattern**: `StripeConnectProperties`
  (`@ConfigurationProperties`, env-var-backed, blank-safe) — reused for the
  new `mrrorigin.notification.email.*` properties.

## Scope boundary (per issue #59)

In scope: email provider integration + ADR, scheduled trigger/lease,
retry/terminal-failure handling, recipient resolution, per-member opt-out
(persistence + authenticated UI/API), delivery audit, tests, docs.

Out of scope (unchanged from #26): summary content/threshold/material-change
rules, CSV export, retained-MRR/NRR in the weekly narrative.

## 1. Scheduling model

### 1a. Trigger mechanism

No scheduler infrastructure exists anywhere in this codebase today — every
existing batch worker (`StripeWebhookNormalizationService.processBatch`,
`StripeBackfillService.runBatch`, `TrackingRetentionService.runBatch`) is
invoked only from tests or from an authenticated per-tenant `POST .../run`
endpoint; there is no `@Scheduled`, no `@EnableScheduling`, no external
cron wiring anywhere in `apps/api`. `ARCHITECTURE.md`'s deployment baseline
places "background workers" in the same process as the API, and its
reliability rules require scheduled work to use database leases. Consistent
with both, and requiring no new infrastructure or ADR-level decision:

- An in-process Spring `@Scheduled` tick (`@EnableScheduling`, e.g. every 5
  minutes) calls `WeeklySummaryDispatchService.dispatchDue()`.
- Horizontal safety (multiple API instances ticking concurrently) comes
  entirely from the DB-backed idempotency/lease design below, exactly as
  `ARCHITECTURE.md` prescribes — the tick itself needs no distributed lock,
  because every write it makes is a conditional, uniquely-constrained
  UPDATE/INSERT that only one instance's statement can win.
- Each tick: for every project, compute that project's next scheduled local
  delivery instant for the most recently completed week (§1b); if `now` has
  passed that instant and no `weekly_summary_deliveries` row exists yet for
  `(project, week_start)`, create one PENDING delivery row per eligible
  recipient (§3) and hand due/retry-eligible rows to the same claim-lease-send
  loop used for retries (§4).
- A manual `POST /api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary/send`
  (manager-only) triggers immediate dispatch for that project's current
  completed week, for support/debugging — reusing the same idempotency key,
  so it can never double-send against a tick that already ran.

### 1b. Weekday, local time, and DST — proposed default

`docs/weekly-summary-export-plan.md`'s "Weekly boundary" already fixes the
week as `[Monday 00:00, next Monday 00:00)` in `Project.timezone` (IANA
zone id). The natural, contract-consistent delivery moment is shortly after
that boundary passes:

- **Proposed: Monday 08:00, `Project.timezone`.** The delivery instant for
  week `W` is `ZonedDateTime.of(W.weekEnd_date, LocalTime.of(8, 0),
ZoneId.of(project.timezone))`, recomputed fresh at dispatch-check time —
  never a stored absolute UTC instant. `java.time.ZoneId` resolves DST
  transitions correctly for this local-wall-clock computation with no
  special-casing: a project in `America/New_York` gets 08:00 America/New_York
  every week, regardless of whether that week crosses a DST boundary, and
  the resulting UTC instant naturally shifts by an hour around the
  transition.
- This is a proposed default, not confirmed product behavior — see
  blocking question B1.

### 1c. Multi-project / multi-currency workspaces

Delivery is **per-project**, one email per project per week per recipient —
not a workspace-level digest. Rationale: `WeeklySummaryService#summary` is
already project-scoped (project timezone, project's own currency sections);
a workspace with projects in different timezones/currencies has no single
correct "week" or currency ordering to merge into one message without
inventing a new aggregation rule PRODUCT.md does not define. A workspace
member on 3 projects who is an eligible recipient on all 3 receives 3
separate emails, each subject-lined with its project name. This matches
§6 of the export plan ("every export and summary section is scoped to one
currency/project at a time; no blending").

## 2. Recipients and roles

### 2a. Default eligible roles — proposed

`WorkspaceRole` is `OWNER > ADMIN > MEMBER > VIEWER`, with `canManage()` true
for `OWNER`/`ADMIN` only. **Proposed: `OWNER` and `ADMIN` are the default
recipients** (i.e. `role.canManage()`), matching the existing precedent that
manage-level roles get elevated defaults (PR #57's `canManage` gate on
sensitive identity data, and the fact that only managers can trigger manual
batch runs like `TrackingRetentionController#run`). `MEMBER`/`VIEWER` do not
receive the summary by default in v1; no opt-in mechanism is in scope (the
issue only asks for opt-out).

This is a product default, not confirmed — see blocking question B2.

### 2b. Recipient scope — workspace-wide members, project-scoped delivery

There is no per-project membership model in this codebase — `WorkspaceMember`
is keyed by `(workspace_id, subject_id)` only, and `Project` belongs to a
workspace with no separate access list. So "recipients" are drawn from
workspace membership (§2a's role filter), and "delivery" is per-project
(§1c). A member is a recipient of _every_ project in their workspace where
their role is eligible and they have not opted out of that specific project
(§3).

### 2c. Recipient email address

`WorkspaceMember.subjectId` is an OIDC subject, not necessarily an email
address. Sending requires a real email per recipient. No email-address field
exists on `WorkspaceMember` today, and `ARCHITECTURE.md`'s deferred-decisions
list names "Production identity provider... topology" as still open. Two
options:

1. Add `email` to `WorkspaceMember` (or a joined identity-provider profile
   fetch) sourced from the OIDC token claims at membership-creation time.
2. Resolve email at send time from the IdP's userinfo/admin API.

Proposed: **(1)** — store `email` on `WorkspaceMember`, populated from the
JWT `email` claim when a member is added/first authenticates, since it
avoids an IdP-API dependency on the hot send path and keeps delivery
resolution a pure DB read (consistent with "no correctness-critical state
only in process memory" plus keeping the send path free of a third external
dependency beyond the email provider itself). Requires a migration adding
a nullable `email` column plus backfill-on-next-auth for existing members;
a member with no captured email yet is simply skipped as a recipient (never
a failed delivery attempt) and this gap is visible in the audit table.

This is a proposed default depending on identity-provider integration
specifics not fully defined elsewhere — see blocking question B3.

## 3. Opt-out

### 3a. Granularity — proposed per (workspace, project, member)

New table `weekly_summary_opt_outs`, modeled on
`project_tracking_retention_settings` (V15)'s "absent row = default" shape:

```sql
CREATE TABLE weekly_summary_opt_outs (
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    opted_out_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, subject_id),
    CONSTRAINT fk_weekly_summary_opt_out_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE
);
```

No row = subscribed (if otherwise eligible per §2a/§2b). A row = opted out
of that specific project. Rationale for project-level (not
workspace-level) granularity: delivery itself is per-project (§1c), and a
member on multiple projects may reasonably want the noisy one silenced
without losing the others — same reasoning DESIGN_SYSTEM.md applies to
every other project-scoped setting.

### 3b. Control surface — proposed

- **Authenticated UI + API only in v1.** `PUT
/api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary/opt-out`
  (body `{optedOut: boolean}`), `requireMembership` (a member manages their
  own subscription; no manager privilege needed to opt out of email one
  receives). `GET` the same path returns current state. UI control lives in
  project settings, per DESIGN_SYSTEM.md's settings conventions.
- **No unauthenticated one-click unsubscribe link in v1** — every mutation
  in this codebase requires a validated JWT (`SecurityConfiguration`
  permits only actuator health, public tracking ingestion, and the Stripe
  OAuth/webhook callbacks unauthenticated), and adding an unauthenticated
  mutation path is a security-boundary change `ARCHITECTURE.md` reserves
  for an explicit decision. Most transactional-email regimes (CAN-SPAM,
  GDPR) expect a functioning one-click unsubscribe even for a logged-out
  recipient, so this gap is a real compliance question, not a minor UX
  preference — see blocking question B4. If a token-based unsubscribe link
  is required, it needs its own signed, single-purpose, non-JWT token
  design (out of this section's proposed default).

## 4. Retry and terminal failure

### 4a. Delivery unit and idempotency key

`weekly_summary_deliveries`, one row per `(workspace_id, project_id,
recipient_subject_id, week_start)` — the idempotency key that prevents
duplicate weekly emails regardless of how many scheduler ticks or manual
triggers race:

```sql
CREATE TABLE weekly_summary_deliveries (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    recipient_subject_id VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    week_start DATE NOT NULL,           -- project-timezone Monday, per #26's boundary
    status VARCHAR(16) NOT NULL,        -- PENDING | SENDING | SENT | FAILED | PERMANENTLY_FAILED
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,      -- lease stamp, same fencing role as stripe_webhook_events
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    provider_message_id VARCHAR(255),   -- set on SENT, for provider-side delivery tracing
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_weekly_summary_delivery_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_weekly_summary_delivery UNIQUE (project_id, recipient_subject_id, week_start),
    CONSTRAINT chk_weekly_summary_delivery_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED')),
    CONSTRAINT chk_weekly_summary_delivery_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_weekly_summary_delivery_due
    ON weekly_summary_deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');
```

Row creation itself is the dedupe point: the scheduler's per-recipient
`INSERT ... ON CONFLICT (project_id, recipient_subject_id, week_start) DO
NOTHING` means a second tick, a manual trigger, or a retry sweep racing the
first can never create a second row for the same project/recipient/week —
mirrors `uq_stripe_webhook_events_mode_event_id`.

### 4b. Claim/send/lease

Directly reuses `StripeWebhookNormalizationService`'s three-phase shape:

1. **Claim** (one short transaction): `SELECT ... FOR UPDATE SKIP LOCKED`
   over `status IN ('PENDING','FAILED') AND next_attempt_at <= now`, `UPDATE
... SET status='SENDING', last_attempted_at=now, attempt_count =
attempt_count + 1` in the same statement — the lease.
2. **Send** (no open transaction): render (`WeeklySummaryRenderer`) and call
   the email provider client. Network call never happens inside a DB
   transaction, matching `StripeBackfillClient`'s established rule.
3. **Apply** (one short transaction, fenced by the claimed `last_attempted_at`
   exactly like `StripeWebhookNormalizationService#applyAndMarkProcessed`):
   on success, `status='SENT'`, store `provider_message_id`; on failure,
   `status='FAILED'`, `last_error`, and `next_attempt_at` advanced per the
   backoff schedule (§4c), or `status='PERMANENTLY_FAILED'` if attempts are
   exhausted.

### 4c. Retry schedule and maximum attempts — proposed

**Proposed: 5 attempts total, backoff 1m / 15m / 1h / 6h / 24h, terminal
`PERMANENTLY_FAILED` after the 5th.** Rationale: covers both a transient
provider blip (retried within the same delivery day) and a longer provider
outage (final retry ~32h after first attempt, safely inside the following
week's own send so failures don't stack), without retrying indefinitely
against a permanently-invalid recipient address. `PERMANENTLY_FAILED` rows
are never deleted or silently dropped (`ARCHITECTURE.md`: "dead records
remain inspectable and replayable") and are replayable via the same manual
manager-triggered endpoint pattern as
`StripeWebhookReplayService#replayEvent`.

This specific schedule is a proposed default — see blocking question B5.

### 4d. Permanent-failure behavior — proposed

On `PERMANENTLY_FAILED`: no further automatic retry; visible in a
manager-facing delivery-status view (data-health-style list, per
DESIGN_SYSTEM's "treat missing evidence honestly" principle applied to
delivery instead of attribution); no end-user-facing alert in v1 beyond
that view (no in-app notification system exists to page a founder about
their own summary failing to send). A manager can manually replay via the
send endpoint. Not silently retried forever, not silently dropped.

## 5. Provider, sender, reply-to

Provider selection, rationale, configuration/secrets model, timeout/error
behavior, and test strategy are in
[ADR-0007](adr/0007-weekly-summary-email-provider.md).

**Sender and reply-to addresses are not decided anywhere in this repo** (no
domain, no support-mailbox convention exists in any doc) — blocking
question B6.

## 6. Delivery audit and retention

`weekly_summary_deliveries` (§4a) _is_ the audit trail — it already records
who, which project, which week, how many attempts, final status, and the
provider's message id, without ever storing the rendered email body/subject
(the content is always reproducible on demand from `WeeklySummaryService` +
`WeeklySummaryRenderer` for the same `week_start`, so storing it again would
duplicate data the export-audit precedent in V17/V18 already avoids for
exported rows).

Retention: **proposed same as V17/V18 — retained for the lifetime of the
workspace, deleted via the `ON DELETE CASCADE` from `projects`, no separate
cleanup job.** If a fixed retention window shorter than "workspace lifetime"
is required for compliance reasons, that needs its own decision — blocking
question B7.

## 7. Authorization

- `GET/PUT .../weekly-summary/opt-out`: `WorkspaceContext#requireMembership`
  (any member manages their own opt-out).
- `POST .../weekly-summary/send` (manual trigger) and any delivery-status
  list view: `WorkspaceContext#requireManager`, matching every other
  batch-trigger endpoint (`TrackingRetentionController#run`,
  `ProjectDataDeletionController`).
- No new public/unauthenticated endpoint (see §3b on the unsubscribe-link
  question).

## Blocking questions (need owner confirmation before implementation)

- **B1 (weekday/time/DST)**: Confirm or override the proposed Monday 08:00
  project-local delivery time (§1b). DST handling itself (recompute from
  `ZoneId` each week, never a stored UTC instant) is not expected to be
  controversial, but the day/time default is a product choice.
- **B2 (default recipient roles)**: Confirm `OWNER`+`ADMIN` as default
  recipients, with `MEMBER`/`VIEWER` excluded by default and no opt-in path
  in v1 (§2a).
- **B3 (recipient email source)**: Confirm storing `email` on
  `WorkspaceMember` sourced from the OIDC token claim (§2c), or specify a
  different source if the identity-provider integration already has one
  planned.
- **B4 (unsubscribe mechanism)**: Confirm authenticated-only opt-out is
  acceptable for v1, or require an unauthenticated one-click unsubscribe
  link (compliance-relevant) — if required, its token design needs to be
  specified before implementation (§3b).
- **B5 (retry schedule)**: Confirm or override the proposed 5-attempt,
  1m/15m/1h/6h/24h backoff schedule (§4c).
- **B6 (provider, sender, reply-to)**: Confirm the ADR-0007 provider
  recommendation, and provide the sender and reply-to addresses to use
  (§5) — no domain/mailbox convention exists anywhere in the repo today.
- **B7 (audit retention)**: Confirm workspace-lifetime retention for
  `weekly_summary_deliveries` (§6), or specify a shorter compliance window.

## Out-of-scope follow-ups (not blocking #59)

- Workspace-level digest rollup across multiple projects.
- In-app/product notification of permanent delivery failure to the founder.
- Bounce/complaint webhook ingestion from the provider (beyond what ADR-0007
  scopes for v1 delivery-status tracking).
