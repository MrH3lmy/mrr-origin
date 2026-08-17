# Weekly summary delivery (#59) — contract-first plan

## Status

**FROZEN — approved by the issue owner on 2026-08-17, corrected the same
day, and further review-corrected on the implementation PR the same day.**
The issue owner's second pass on B3, B5, B6, and the delivery-guarantee
language changed the contract below; B1, B2, B4's core (authenticated-only
opt-out), and B7's mechanism are otherwise unchanged from the first
approval. A subsequent blocking code review on PR #60 found the _initial
implementation_ of the corrected contract did not fully match it in eight
places (opt-out/role not revalidated before a retry send, an overstated
"claims can never both send" guarantee, ambiguous-outcome tracking cleared
by a later success, a project-scheduling cap that permanently starved
projects beyond it, a manual-send path that bypassed the
unconfigured-provider gate, an incomplete Postmark permanent/transient
classifier, a missing membership-removal cascade on opt-outs, and a
timezone-sensitive date display) — those are now fixed in both code and in
this document (§1a, §4b, §4d, §5, "Delivery guarantee", and the `CANCELLED`
status). Section headings below are left as originally written (including
the word "proposed") so the rationale stays attached to each decision; the
"Blocking questions" section at the end records the corrected, final
resolution for each one. This is the authoritative contract for #59's
implementation, per the same precedent `docs/weekly-summary-export-plan.md`
set for #26.

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
  loop used for retries (§4). Projects are walked via real keyset pagination
  (ordered by id, page-by-page within the tick), not a fixed count-and-stop
  cap over an always-unpaged read — a prior version capped at 500 projects
  per tick while re-reading the same unpaged set every time, which
  permanently starved every project beyond the cap rather than picking them
  up "next tick" as its own log message claimed (review-corrected).
- A manual `POST /api/workspaces/{workspaceId}/projects/{projectId}/notifications/weekly-summary/send`
  (manager-only) triggers immediate dispatch for that project's current
  completed week, for support/debugging — reusing the same idempotency key,
  so it can never double-send against a tick that already ran. Rejects with
  `503` before creating or claiming any work if email sending is not
  configured (review-corrected — a prior version bypassed the same
  `isConfigured()` gate the scheduled tick honors, so a blank
  token/sender/base-URL deployment could still create rows, claim them, and
  record failures while returning `200`).

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

**(1)**, corrected — store `email` on `WorkspaceMember`, sourced **only**
from a JWT whose `email_verified` claim is `true`; an unverified or absent
claim never overwrites or seeds the stored value. This avoids an IdP-API
dependency on the hot send path and keeps delivery resolution a pure DB
read, while never trusting an address the identity provider itself has not
confirmed the member controls. The stored email is **refreshed** — not just
captured once — every time that subject authenticates with a verified claim
whose value differs from what is stored, so a member who changes their
verified email address is picked up automatically. A manager-supplied
address is never authoritative; there is no admin-editable email field.

A member with no verified email yet is **not** silently skipped: they are
still an eligible recipient (§2a), and the scheduler records an auditable
`BLOCKED_MISSING_EMAIL` delivery row for them instead of creating no row at
all — visible to managers in the delivery-status view (§4d) exactly like
any other non-`SENT` outcome. Once a verified email becomes available (next
authentication), a manager can manually replay that row (§4c) to send the
week's summary retroactively; nothing does this automatically, since replay
requires a manager decision the same way any other terminal-state replay
does.

This is now a corrected, final decision — see blocking question B3.

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
- **No unauthenticated one-click unsubscribe link in v1, confirmed** — every
  mutation in this codebase requires a validated JWT (`SecurityConfiguration`
  permits only actuator health, public tracking ingestion, and the Stripe
  OAuth/webhook callbacks unauthenticated), and adding an unauthenticated
  mutation path is a security-boundary change `ARCHITECTURE.md` reserves
  for an explicit decision. Accepted for v1 on the condition that these
  emails remain **strictly operational/reporting content** — no promotions,
  advertisements, or upsell copy — which is the factor that made an
  authenticated-only opt-out acceptable without a logged-out one-click path.
  One-click unsubscribe (its own signed, single-purpose, non-JWT token
  design) is tracked as a required follow-up **before** these emails ever
  carry marketing content or become a broader subscription campaign — not
  optional polish, a hard precondition on scope expansion.
- **Every email must contain a direct link to the authenticated opt-out
  setting** (this project's weekly-summary settings page, §5), so a
  recipient who wants to stop receiving it never has to hunt for the
  control — it just requires them to be signed in to use it.

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
    recipient_email VARCHAR(320),       -- null exactly while status = BLOCKED_MISSING_EMAIL
    week_start DATE NOT NULL,           -- project-timezone Monday, per #26's boundary
    status VARCHAR(24) NOT NULL,        -- PENDING | SENDING | SENT | FAILED | PERMANENTLY_FAILED | BLOCKED_MISSING_EMAIL | CANCELLED
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,      -- informational; fencing uses lease_token, not this
    lease_token UUID,                   -- random per claim, fences markSent/markFailed
    lease_until TIMESTAMPTZ,            -- claim expiry; an expired SENDING lease is reclaimable
    last_outcome_ambiguous BOOLEAN NOT NULL DEFAULT FALSE, -- accumulates (OR) across attempts, never reset by a later definite outcome
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,                    -- sanitized, length-bounded; never a credential/body/raw response
    provider_message_id VARCHAR(255),   -- set on SENT, for provider-side delivery tracing
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_weekly_summary_delivery_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_weekly_summary_delivery UNIQUE (project_id, recipient_subject_id, week_start),
    CONSTRAINT chk_weekly_summary_delivery_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED', 'BLOCKED_MISSING_EMAIL', 'CANCELLED')),
    CONSTRAINT chk_weekly_summary_delivery_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_weekly_summary_delivery_email_presence
        CHECK ((status = 'BLOCKED_MISSING_EMAIL') = (recipient_email IS NULL))
);

CREATE INDEX idx_weekly_summary_delivery_due
    ON weekly_summary_deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_weekly_summary_delivery_reclaim
    ON weekly_summary_deliveries (lease_until)
    WHERE status = 'SENDING';
```

Row creation itself is the dedupe point: the scheduler's per-recipient
`INSERT ... ON CONFLICT (project_id, recipient_subject_id, week_start) DO
NOTHING` means a second tick, a manual trigger, or a retry sweep racing the
first can never create a second row for the same project/recipient/week —
mirrors `uq_stripe_webhook_events_mode_event_id`. This is an **internal**
dedupe guarantee only — see "Delivery guarantee" below for what it does and
does not promise about the provider side.

### 4b. Claim/send/lease

Directly reuses `StripeWebhookNormalizationService`'s three-phase shape,
corrected to use an explicit random lease token rather than a timestamp so
an expired lease is unambiguously reclaimable:

1. **Claim** (one short transaction): `SELECT ... FOR UPDATE SKIP LOCKED`
   over rows that are either due (`status IN ('PENDING','FAILED') AND
next_attempt_at <= now`) or an **expired lease** (`status = 'SENDING' AND
lease_until <= now` — a prior worker that claimed the row and then died,
   restarted, or was killed before recording an outcome). `UPDATE ... SET
status='SENDING', lease_token=<new random UUID>, lease_until=now +
   <lease duration>, last_attempted_at=now, attempt_count = attempt_count +
   1` in the same statement — the lease. `BLOCKED_MISSING_EMAIL` rows are
   never claimable (no `recipient_email` to send to).
2. **Revalidate, then send** (no open transaction): immediately before
   rendering/sending, re-check the recipient is still not opted out and
   still holds a manage-level role right now — not only at whatever earlier
   time this row was created (review-corrected: a member who opts out, or
   is demoted/removed, while a row sits `FAILED` in backoff must not still
   receive a later retry). If no longer eligible, the row is fenced
   straight to `CANCELLED` (§4a's status list) and the provider is never
   called. Otherwise, re-check the claimed `lease_token` is still current
   (a cheap pre-send freshness read) before calling the provider — this
   narrows, but for a merely-paused (not dead) original worker cannot fully
   close, the window described in the delivery guarantee below. Render
   (`WeeklySummaryRenderer`) and call the email provider client; the
   network call never happens inside a DB transaction, matching
   `StripeBackfillClient`'s established rule.
3. **Apply** (one short transaction, fenced by `status = 'SENDING' AND
lease_token = <the exact token claimed in step 1>` — a worker whose lease
   was since reclaimed can never overwrite a newer outcome _in the
   database_, though it may still have made its own outbound provider call;
   see the delivery guarantee below): on success, `status='SENT'`, store
   `provider_message_id`, clear the lease, and leave `last_outcome_ambiguous`
   untouched (it accumulates across attempts, see below — never reset by a
   later success); on failure, `status='FAILED'`, sanitized/bounded
   `last_error`, `last_outcome_ambiguous` OR'd with this attempt's
   provider-failure classification (see "Delivery guarantee" below),
   `next_attempt_at` advanced per the backoff schedule (§4c), lease
   cleared, or `status='PERMANENTLY_FAILED'` if attempts are exhausted or
   the failure was classified permanent.

### 4c. Retry schedule and maximum attempts — corrected

**6 attempts total, backoff 1m / 15m / 1h / 6h / 24h between attempts 1-5,
terminal `PERMANENTLY_FAILED` after the 6th.** (Corrected from the first
approval's 5 attempts — the same 1m/15m/1h/6h/24h backoff shape, one more
attempt appended at the end of the schedule rather than changing its
cadence.) Rationale unchanged: covers both a transient provider blip and a
longer provider outage without retrying indefinitely against a
permanently-invalid recipient address. `PERMANENTLY_FAILED` rows are never
deleted before the retention window (§6) and are replayable via a
manager-triggered replay endpoint, the same shape as
`StripeWebhookReplayService#replayEvent`. A provider response classified
**permanent** (§5) short-circuits straight to `PERMANENTLY_FAILED`
regardless of remaining attempt budget — retrying a hard-invalid address
six times over 24h+ wastes attempts and cannot succeed.

This is now a corrected, final decision — see blocking question B5.

### 4d. Permanent-failure, blocked, and cancelled behavior — corrected

On `PERMANENTLY_FAILED` or `BLOCKED_MISSING_EMAIL`: no further automatic
retry; both are visible in a manager-facing delivery-status view
(data-health-style list, per DESIGN_SYSTEM's "treat missing evidence
honestly" principle applied to delivery instead of attribution); no
end-user-facing alert in v1 beyond that view (no in-app notification system
exists to page a founder about their own summary failing to send). A
manager can manually replay either kind via the same replay endpoint:

- Replaying a `PERMANENTLY_FAILED` row resets it to `PENDING` with a fresh
  attempt budget and clears `last_error`, while preserving
  `last_outcome_ambiguous` because it is cumulative across the delivery
  row's full lifetime and replay must not erase earlier ambiguity.
- Replaying a `BLOCKED_MISSING_EMAIL` row re-checks the member's currently
  stored (verified) email; if one is now present, the row is populated with
  it and moved to `PENDING`; if still absent, the replay is rejected with a
  clear reason rather than silently no-op'ing.

**`CANCELLED` (review-corrected addition)**: a claimed row whose recipient
is revalidated — right before send, not only at row-creation time — to no
longer be eligible (opted out, or lost/removed their manage-level role)
while it sat `FAILED` in backoff. The provider is never called for a
cancelled row. Terminal and **not** replayable: the recipient's current
state is by definition ineligible at the moment this status is set, so
replaying it would contradict their own opt-out or role change; the
following week creates its own fresh row as usual once/if they become
eligible again. Without this, a member who opts out mid-retry (or is
demoted/removed) could still receive a stale summary from an in-flight
backoff cycle that predates their change — a real gap in an earlier
version, not a hypothetical.

Not silently retried forever, not silently dropped.

## 5. Provider, sender, reply-to

Provider selection, rationale, configuration/secrets model, timeout/error
behavior, and test strategy are in
[ADR-0007](adr/0007-weekly-summary-email-provider.md).

**Sender/reply-to naming convention, corrected**: `weekly-summary@<verified
product domain>` (sender) and `support@<verified product domain>`
(reply-to). The domain itself is never hardcoded — each deployment supplies
its own verified domain via `WEEKLY_SUMMARY_SENDER_ADDRESS` /
`WEEKLY_SUMMARY_REPLY_TO_ADDRESS` (unchanged mechanism from the first
approval), this section only fixes the local-part convention operators
should follow when they set those values. Tests use a reserved,
non-deliverable domain (`weekly-summary@example.test`) per
[RFC 2606](https://www.rfc-editor.org/rfc/rfc2606), never a real or
plausible-looking address — blocking question B6.

## 6. Delivery audit and retention

`weekly_summary_deliveries` (§4a) _is_ the audit trail — it already records
who, which project, which week, how many attempts, final status, and the
provider's message id, without ever storing the rendered email body/subject
(the content is always reproducible on demand from `WeeklySummaryService` +
`WeeklySummaryRenderer` for the same `week_start`, so storing it again would
duplicate data the export-audit precedent in V17/V18 already avoids for
exported rows). `last_error` is sanitized and length-bounded before storage
(truncated, no credential/message-body/raw-provider-response content ever
written to it — only our own classification text and Postmark's opaque
numeric `ErrorCode`/short `Message` field).

Retention: **corrected — 400 days for terminal rows, not workspace
lifetime.** A daily cleanup pass (reusing the same in-process `@Scheduled`
mechanism as dispatch, §1a) deletes rows whose status is `SENT`,
`PERMANENTLY_FAILED`, `BLOCKED_MISSING_EMAIL` (a "superseded" blocked
record — one that was never resolved before aging out), or `CANCELLED`
(§4d) and whose `created_at` is older than 400 days. `PENDING`, `SENDING`,
and retryable `FAILED` rows are **never** touched by retention cleanup
regardless of age
— those are active work, not audit history. The `ON DELETE CASCADE` from
`projects` still applies (a deleted project's rows go with it immediately,
unrelated to the 400-day window). `weekly_summary_opt_outs` rows are
retained until the membership/project is removed or the member opts back
in — opt-out is a standing preference, not a delivery-attempt record, so it
is not subject to the same time-based cleanup.

This is now a corrected, final decision — see blocking question B7.

## Delivery guarantee — corrected, required

**This system provides at-least-once delivery, not exactly-once, and it
does not claim two internal claim attempts can never both send** (an
earlier version of this section overstated that — review-corrected). The
`uq_weekly_summary_delivery` constraint (§4a) prevents duplicate
_scheduling_: two ticks, a tick racing a manual trigger, or two claim
attempts can never both create a row or both _apply_ an outcome to the same
row in the database. It does not, by itself, prevent two _sends_ to the
provider — there are two distinct, independent reasons a duplicate send can
still rarely happen, and both are accepted possibilities, not bugs to be
engineered away:

1. **Ambiguous network outcomes.** Postmark's public API does not document
   a request-idempotency key, so if Postmark accepts and queues a message
   but the HTTP response is lost (timeout, connection reset, proxy failure)
   before we record the outcome, our only safe action on retry is to send
   again.
2. **Lease-reclaim racing a merely-paused worker (§4b, review-corrected).**
   An expired `SENDING` lease is reclaimed on the assumption the original
   worker died. If it was instead only paused (GC, scheduler preemption,
   a slow thread) and later resumes, it can still complete its own
   provider call after a second worker has already claimed and sent the
   same row — the `lease_token` fences the database apply, not the
   in-flight outbound HTTP call, which cannot be preempted from the
   database side. A pre-send freshness check (§4b) narrows this window but
   cannot fully close it for a worker that resumes between that check and
   its actual network call.

Concretely:

- **At-least-once delivery.** A recipient may, rarely, receive the same
  week's summary twice, from either source above.
- **Internal duplicate scheduling and duplicate database outcomes are
  prevented** by the unique key and lease-token fencing (§4a/§4b) — this
  part is a real, enforced guarantee. Duplicate _provider calls_ are not
  fully prevented, for the two reasons above.
- Every send includes the delivery row's UUID in the Postmark request's
  `Metadata` object and a custom `X-MRR-Origin-Delivery-Id` tracing header,
  so any duplicate that does occur is traceable back to the exact delivery
  attempt on both sides (our audit trail and Postmark's own dashboard/logs).
- Ambiguous outcomes are recorded distinctly and **cumulatively**
  (`last_outcome_ambiguous`, §4a) — true if _any_ attempt across this
  delivery's lifetime was ambiguous, never reset to false by a later
  definite success or failure (review-corrected: an earlier version cleared
  it on success, silently erasing the evidence that a duplicate was
  possible) — and every retry attempt remains in the same audit trail (§6)
  whether or not it turns out to have been necessary.
- **No documentation, code comment, or test in this codebase claims
  exactly-once delivery, or that duplicate provider calls are impossible.**
  Any future wording implying otherwise is a defect against this contract.

## 7. Authorization

- `GET/PUT .../weekly-summary/opt-out`: `WorkspaceContext#requireMembership`
  (any member manages their own opt-out).
- `POST .../weekly-summary/send` (manual trigger) and any delivery-status
  list view: `WorkspaceContext#requireManager`, matching every other
  batch-trigger endpoint (`TrackingRetentionController#run`,
  `ProjectDataDeletionController`).
- No new public/unauthenticated endpoint (see §3b on the unsubscribe-link
  question).

## Blocking questions — resolved 2026-08-17, corrected 2026-08-17

- **B1 (weekday/time/DST) — ACCEPTED, unchanged**: Monday 08:00
  project-local delivery time (§1b), recalculated every week via `ZoneId`
  (never a stored UTC instant), covering the immediately preceding
  completed Monday-to-Monday week.
- **B2 (default recipient roles) — ACCEPTED, unchanged**: `OWNER`+`ADMIN`
  are the default recipients, `MEMBER`/`VIEWER` excluded by default,
  delivery per-project, opt-out per (workspace, project, member) (§2a).
- **B3 (recipient email source) — ACCEPTED WITH REFINEMENT**: `email`
  stored on `WorkspaceMember`, sourced only from a JWT with
  `email_verified=true`, refreshed on every authentication where the
  verified claim changes; never a manager-supplied address. A member
  without a verified email is not silently ignored — an auditable,
  manager-visible, manually-replayable `BLOCKED_MISSING_EMAIL` delivery
  state is recorded instead (§2c, §4a, §4d).
- **B4 (unsubscribe mechanism) — ACCEPTED, unchanged for v1**:
  authenticated project-settings UI/API opt-out; every email must link
  directly to it; content stays strictly operational/reporting (no
  marketing); no unauthenticated one-click unsubscribe endpoint in v1,
  tracked as a required follow-up before marketing content or a broader
  campaign (§3b).
- **B5 (retry schedule) — CORRECTED**: 6 total attempts, 1m/15m/1h/6h/24h
  backoff between attempts 1-5, then `PERMANENTLY_FAILED`; permanent
  provider/address failures short-circuit immediately; managers may replay
  terminal failures; claiming uses an explicit random `lease_token` +
  `lease_until` (reclaimable on expiry), fencing success/failure by the
  exact lease token, never the provider network call inside a DB
  transaction (§4b, §4c).
- **B6 (provider, sender, reply-to) — ACCEPTED WITH NAMING CONVENTION**:
  Postmark per ADR-0007, plain `RestClient`, no vendor SDK.
  `weekly-summary@<verified-domain>` (sender) /
  `support@<verified-domain>` (reply-to) is the naming convention; the
  domain itself remains purely operator-configured
  (`WEEKLY_SUMMARY_SENDER_ADDRESS` / `WEEKLY_SUMMARY_REPLY_TO_ADDRESS`), so
  the API continues to start cleanly with these blank and the scheduler
  skips dispatch with a one-time logged warning until an operator sets them
  for a given deployment. Tests use reserved `example.test` addresses (§5,
  ADR-0007 "Migration and operational consequences").
- **B7 (audit retention) — CORRECTED**: 400-day retention for terminal
  (`SENT`/`PERMANENTLY_FAILED`/`BLOCKED_MISSING_EMAIL`) rows via a daily
  cleanup pass, not workspace lifetime; active (`PENDING`/`SENDING`/
  retryable `FAILED`) rows are never touched by cleanup; no rendered
  subject/body ever persisted; `last_error` sanitized and length-bounded;
  opt-out rows retained until membership/project removal or opt-back-in
  (§6).
- **Delivery guarantee — CORRECTED, required**: at-least-once, not
  exactly-once; internal duplicate scheduling prevented by the DB unique
  key; rare provider-side duplicates accepted after ambiguous network
  outcomes; delivery UUID carried in Postmark `Metadata` and a custom
  tracing header; ambiguous outcomes and every retry recorded in the audit
  trail; no documentation, code, or test may claim exactly-once delivery
  (see "Delivery guarantee" above).

## Out-of-scope follow-ups (not blocking #59)

- Workspace-level digest rollup across multiple projects.
- In-app/product notification of permanent delivery failure to the founder.
- Bounce/complaint webhook ingestion from the provider (beyond what ADR-0007
  scopes for v1 delivery-status tracking).
