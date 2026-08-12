# ADR-0005: Attribution model, evidence, confidence, and window

- Status: Accepted
- Date: 2026-08-12

## Context

Phase 4 needs first-touch and last-touch attribution before any engine code is
written (#18) or reports are built. `ARCHITECTURE.md`'s evidence table and
`PRODUCT.md`'s "Evidence over false precision" principle already commit to
deterministic-only evidence and an explicit `Unattributed` state, but leave the
selection rule, direct-traffic handling, window shape, conflict behavior, and
model-versioning contract as deferred decisions. Two upstream pieces are now in
place and constrain what this ADR can assume:

- V2's tracking schema (`visitors`, `tracking_sessions`, `touchpoints`) and V6's
  identity schema (`external_identities`, `visitor_aliases`) — `identify()`
  (#8) allows exactly one immutable alias per `(project_id, visitor_id)`, but an
  `external_identity_id` can be the target of many `visitor_aliases` rows, so a
  single application user can already be linked from more than one anonymous
  visitor.
- V8's `stripe_customer_links` (#16) — an explicit, workspace-manager-authenticated
  bridge from `external_identities` to `billing_customers`, evidenced as
  `EXPLICIT_API`, with `STRIPE_METADATA` reserved (billing does not persist
  Stripe `metadata` yet) and at most one active link per identity and per
  Stripe customer enforced by partial unique indexes.
- ADR-0004's MRR movement classification (New, Expansion, Contraction, Churn,
  Reactivation), which this ADR must attach evidence to without changing.

This ADR defines the contract; #18 implements the engine against it and owns
any migration for stored attribution results.

## Decision

### Eligible acquisition touchpoints

A touchpoint (V2 `touchpoints` row) is eligible evidence for a customer's
acquisition attribution when all of the following hold:

- It belongs to a visitor that has a `visitor_aliases` row into the
  `external_identity` that resolves (directly or through a merge, see below)
  to the Stripe customer behind the MRR movement being attributed.
- `occurred_at` falls inside the attribution window computed for that movement
  (see Window below).
- `workspace_id` and `project_id` on the touchpoint match the workspace and
  project that own the identity link. Cross-project touchpoints are never
  pooled, even within the same workspace, because `identify()` and
  `stripe_customer_links` are project-scoped.

A touchpoint with no `landing_url` cannot exist (V2 requires it `NOT NULL`);
one with a null `referrer_url` and no UTM parameters is eligible evidence, but
is classified as **direct** (see below), not discarded.

### Touchpoint pool: identity merges and multiple visitor aliases

Because `visitor_aliases` allows many visitors to point at one
`external_identity_id`, the eligible pool for a customer is the **union** of
touchpoints across every visitor with an alias into that identity — not
just the visitor active at conversion time. A visitor merge (the founder's
customer browsed anonymously on two devices, then called `identify()` from
both) does not create a new evidence tier; it simply widens the pool that
first-touch and last-touch select from. This is why `identify()` is a
prerequisite for `Strong` evidence to reach any touchpoint at all — an
identity with no alias contributes no touchpoints, only its own link record.

### First-touch selection

Among the eligible pool, first-touch is the touchpoint with the earliest
`occurred_at`. Ties (identical `occurred_at`, e.g. two events queued in the
same batch) are broken by earliest `created_at` (ingestion order), then by the
lower touchpoint `id` as a final, arbitrary but stable tiebreak. The same
three-key ordering is used everywhere below so the rule is deterministic and
replay-stable — reprocessing identical input must select the same touchpoint.

### Last-touch selection

Among the eligible pool, last-touch is the touchpoint with the latest
`occurred_at` that is not later than the movement's effective timestamp (a
touchpoint cannot cause an acquisition that already happened). Ties are broken
by latest `created_at`, then by the higher touchpoint `id`.

### Direct-traffic handling

A touchpoint is **direct** when `referrer_url` is null or blank after trimming and all
five UTM fields (`utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, and
`utm_content`) are null or blank after trimming. Any populated UTM field makes the
touchpoint non-direct. Direct touchpoints are eligible evidence, and first-touch
and last-touch treat them asymmetrically on purpose:

- **First-touch** is simply the earliest eligible touchpoint, direct or not,
  with no special-casing. First-touch answers "how did they discover us," and
  a direct first visit (typed the URL, opened a bookmark, already knew the
  brand) is a legitimate, truthful answer to that question — not a gap to look
  past in search of an earlier "real" channel, because there isn't one.
- **Last-touch** is the latest **non-direct** eligible touchpoint at or before
  the movement's effective timestamp, if one exists. A later direct touchpoint
  (the customer typed the URL again, or came back from a bookmark, shortly
  before converting) is treated as a continuation of the same visit history,
  not a new source, and does not become last-touch. Last-touch resolves to a
  direct touchpoint only when the entire eligible pool is direct.

This is the "last non-direct touch" rule, applied only to last-touch: a direct
return after an earlier non-direct acquisition keeps attributing last-touch to
that earlier channel, because last-touch answers "what channel is behind this
conversion," and a direct pageview is never itself a channel to report on when
a real one is known. First-touch has no equivalent look-past rule — it is
always the earliest eligible touchpoint, full stop.

### Attribution-window anchor, duration, inclusivity, and expiration

- **Anchor**: the window is computed once, anchored on the effective timestamp
  of the customer's first New MRR movement (the acquisition event, per
  ADR-0004's classification table) — never on `identify()` time, signup time,
  or ingestion/webhook receipt time. Reactivation, Expansion, Contraction, and
  Churn movements do not compute their own window (see below).
- **Duration**: the default window is **90 days** before the anchor. This is a
  V1 default, not a hardcoded constant the product is committed to forever;
  making it project-configurable is explicitly deferred to #18 and does not
  require another ADR, since it does not change how a window is evaluated —
  only its length.
- **Inclusivity**: the window is closed on both ends —
  `[anchor - duration, anchor]`. A touchpoint at exactly `anchor - duration` is
  eligible; a touchpoint at exactly `anchor` is eligible (the visit and the
  conversion can share an instant only in synthetic fixtures, but the rule
  must still be unambiguous); a touchpoint strictly after `anchor` is never
  eligible, because a future touchpoint cannot have caused a past acquisition.
- **Expiration**: a touchpoint older than `anchor - duration` is not evidence.
  It is not partially weighted, not used as a fallback when nothing else
  exists, and not surfaced as a lower-confidence guess — it is simply outside
  the pool, and if it was the only touchpoint available the result is
  `Unattributed` (see No-evidence behavior).

### Delayed conversions and multiple-session behavior

Because the window anchors on the acquisition effective timestamp rather than
on touchpoint or session time, a delayed conversion (browse, leave, sign up
and pay weeks later) is handled by the same rule as an immediate one: every
eligible touchpoint inside `[anchor - duration, anchor]` is pooled regardless
of which session produced it. Multiple sessions from the same visitor, or from
merged visitors, are pooled together and ordered as described above; sessions
themselves are not a unit of attribution, only a grouping visible in the
customer evidence timeline.

Identity evidence is evaluated at calculation time, not frozen at the acquisition
instant. An `identify()` call or a second-device alias created after New MRR may therefore
make older, in-window touchpoints eligible on recalculation. This is deliberate late-link
repair: the touchpoint timestamp must still precede the acquisition anchor, and the alias
and customer link remain inspectable. The engine never rewrites the historical touchpoint
or pretends the identity was known earlier.

### Evidence precedence: Verified, Strong, Moderate, Unattributed

Every confidence label maps to one inspectable, deterministic, stored record —
never a score, a probability, or an unstored inference:

| Confidence       | Stored evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Touchpoint pool source                                                                                                                                                                  |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Verified**     | A visitor identifier explicitly present in Stripe object metadata (e.g. `Customer.metadata`, a Checkout Session's `client_reference_id`) that matches a `visitors.external_visitor_id` row 1:1, recorded as a `stripe_customer_links` row with `evidence_source = STRIPE_METADATA` and `evidence_reference` pointing at the exact metadata field and Stripe object ID read. Reserved by V8; not populated until billing persists Stripe metadata (tracked separately from #18).                                                   | The visitor named directly by the metadata, plus any other visitor merged into the same `external_identity` via `visitor_aliases`, if the metadata visitor itself was later identified. |
| **Strong**       | An `identify(externalUserId)` call recorded in `visitor_aliases`, bridged to a Stripe customer by an explicit, authenticated `stripe_customer_links` row with `evidence_source = EXPLICIT_API` (#16). `evidence_reference` is that link row's `id`.                                                                                                                                                                                                                                                                               | Every visitor with a `visitor_aliases` row into that `external_identity`.                                                                                                               |
| **Moderate**     | An approved deterministic alias. The V1 contract reserves an exact match of a workspace-keyed HMAC computed server-side from an explicitly supplied, lowercased and trimmed email on both sides. Raw email and an unkeyed, enumerable email hash must not be stored as evidence. The capture/API path, key management, and evidence table are future scope; Moderate is not produced by #16 or #18 until that storage exists. Any similarity, edit-distance, partial-match, or inferred-email scheme is permanently out of scope. | Same shape as Strong once the alias schema exists.                                                                                                                                      |
| **Unattributed** | No active link of any of the above kinds, or a link exists but its touchpoint pool has no eligible touchpoint inside the window.                                                                                                                                                                                                                                                                                                                                                                                                  | None.                                                                                                                                                                                   |

Confidence is a property of the **link**, not of how many touchpoints it
happens to produce — a Strong link with zero eligible touchpoints in the
window is `Unattributed` for attribution purposes (no source to report) while
remaining a valid Strong identity link for other product surfaces (the
customer evidence timeline still shows the identity bridge; it just has no
acquisition source to show next to it).

### Conflict behavior when deterministic evidence disagrees

V8 permits at most one non-superseded `stripe_customer_links` row per external
identity and per Stripe customer, regardless of evidence tier. Consequently, the
attribution engine never receives two active link rows for one customer and must not
invent a precedence contest that the schema cannot represent.

Conflict behavior applies while deterministic evidence candidates are being resolved:

1. **Same-tier conflict** — candidates at the same tier that disagree are rejected.
   The existing active link, if any, remains unchanged; no attribution is recalculated
   from the rejected candidate.
2. **Cross-tier agreement** — when candidates at different tiers resolve to the same
   external identity and therefore the same touchpoint pool, the highest tier wins:
   Verified, then Strong, then Moderate.
3. **Cross-tier disagreement** — when a higher- and lower-tier candidate imply
   different identities or disjoint pools, the higher tier is the proposed winner, but
   the lower-tier pool is never blended in or used to fill a gap. Before any automatic
   supersession, the resolver must durably record the conflict and the winning and
   losing evidence references. Until that conflict-recording write path exists, the
   attempt is rejected and the existing active link remains authoritative.

This separates candidate resolution from stored active-link selection and matches V8's
uniqueness constraints. Blending tiers, picking the pool with more touchpoints, or
picking the most recent pool are explicitly rejected.

### No-evidence behavior

When no active link exists at all, or an active link's touchpoint pool is
empty for the window, the stored attribution result is `Unattributed` with
null evidence references and a stable reason: `NO_ACTIVE_LINK` or
`NO_ELIGIBLE_TOUCHPOINT`. `Unattributed` is a first-class, permanently visible
result per `PRODUCT.md`'s evidence principle — it is never converted to
"direct," omitted from reports, or backfilled with a guess.

### Model versioning, stored evidence references, recalculation, and backward compatibility

- Every derived attribution result stores a `model_version` string, references
  to the evidence it used (the `stripe_customer_links` row id, and the
  selected touchpoint id(s) for first-touch/last-touch), a computed
  `confidence`, an `unattributed_reason` when applicable, and a `calculated_at`
  timestamp — mirroring
  `ARCHITECTURE.md`'s attribution-path description and ADR-0002's
  raw/derived split. Raw touchpoints, aliases, and links are never mutated by
  attribution; only derived result rows change.
- A model-version change (a rule in this ADR being revised — window duration,
  direct-traffic handling, tie-break order, a new evidence tier) requires a
  new `model_version` and a full recalculation of affected customers' New MRR
  attribution. Recalculation produces a new derived result row; it does not
  overwrite or delete the prior one, consistent with ADR-0002 — the prior
  version remains inspectable, and reports must be able to say which model
  version produced a shown result.
- Backward compatibility means a stored result must remain explainable without
  re-running the engine: its evidence references point at immutable rows
  (touchpoints, links) that never change shape retroactively. A schema change
  to evidence storage itself (e.g. adding the Moderate tier's alias table)
  requires a new model version, not a silent reinterpretation of old results
  under the new rule.
- Recalculation is idempotent: recalculating with the same model version
  against unchanged inputs produces byte-identical evidence references and
  confidence, so a scheduled or triggered replay is safe to run repeatedly.

### New versus Reactivation MRR

Attribution answers "where did this customer originate," which is computed
**once**, anchored on the customer's first New MRR movement, exactly as
described in Window above. Reactivation MRR — a return to positive MRR after
the customer previously reached zero (ADR-0004) — does not compute a new
window or search for touchpoints near the reactivation timestamp. It **inherits**
the customer's original New MRR attribution result (same evidence references,
same confidence, same model version at time of last recalculation).

This is a deliberate product-scoped decision, not just an implementation
shortcut: `PRODUCT.md`'s job 2 asks the product to "show the acquisition
source behind the ... reactivated MRR," and job 1 defines that as "where that
customer originally came from" — the origin does not change because the
customer churned and returned. Touchpoints occurring near a reactivation are
re-engagement evidence (useful for a future "win-back channel" feature), not
acquisition evidence, and this ADR does not define a re-engagement model. If
product direction later wants reactivation attributed to a _different_ signal
than original acquisition, that is a product-scope change requiring its own
ADR revision, not an engine-level judgment call.

### Expansion, Contraction, and Churn inheritance

Expansion, Contraction, and Churn movements (ADR-0004) are changes on an
already-acquired customer, not new acquisition events. They **inherit** the
same acquisition attribution result as the customer's New MRR movement — same
evidence, same confidence, same model version — rather than triggering their
own touchpoint search. No rule in this ADR ever attributes a contraction or
churn event to "the touchpoint nearest the cancellation," because there is no
product job asking for that, and doing so would misrepresent a billing-state
change as if a marketing touch caused it. A future retention/churn-driver
feature that wants a different signal is out of this ADR's scope and needs its
own decision.

### Explicitly rejected identity-resolution techniques

Per `ARCHITECTURE.md`'s tenancy/security baseline and `AGENTS.md`'s
architecture rules, the following are permanently out of scope for attribution
evidence, not merely deferred:

- **IP address matching** — no IP address is stored or compared for identity
  or attribution purposes.
- **Device fingerprinting** — no canvas, font, header-combination, or other
  passive fingerprinting signal is collected or used.
- **Email guessing** — Moderate evidence requires an exact match of
  workspace-keyed HMACs computed server-side from emails explicitly supplied to both
  systems. It never infers, guesses, fuzzy-matches, or stores a raw or unkeyed email
  hash as attribution evidence.
- **Any probabilistic identity resolution** — no scoring, weighting, or
  "most likely" match of any kind contributes to confidence or touchpoint
  selection anywhere in this ADR. Every selection rule above is a deterministic
  function of stored rows; the same inputs always produce the same output.

## Consequences

- #18 can implement the engine directly against this contract: pool
  construction, first/last-touch selection, direct handling, window
  evaluation, and confidence precedence are fully specified. Strong and Unattributed are
  implementable from current storage; Verified and Moderate stay disabled until their
  explicitly deferred evidence writers and schemas exist.
- A 90-day default window and last-non-direct-touch handling are now committed
  V1 behavior; changing either later is a model-version bump under this ADR,
  not a new ADR, unless the change also touches which evidence tiers exist.
- Reactivation, Expansion, Contraction, and Churn movements never run their
  own attribution computation — they are cheap to attach evidence to, but a
  customer's acquisition evidence must remain queryable/inspectable
  indefinitely for this inheritance to stay correct, reinforcing ADR-0002's
  immutability requirement on raw tracking and link data.
- The Moderate tier and Verified's `STRIPE_METADATA` source are specified but
  not yet backed by data (no email-hash alias table exists; billing does not
  persist Stripe metadata yet) — until then, real attribution results in V1
  can only be Strong or Unattributed. This is expected, not a gap in this ADR.
- V8 prevents multiple active links for one customer. A future evidence writer must
  durably record cross-tier disagreements before superseding an active link; #18 does
  not need to model impossible simultaneous active links.

## Open product decisions

- **Default window duration (90 days)** is this ADR's default, not a
  founder-validated number; product should confirm it against real beta usage
  before or shortly after #18 ships, and/or prioritize the per-project
  override mentioned above.
- **Reactivation inheriting original acquisition evidence** (rather than
  re-attributing to whatever channel brought the customer back) is the
  reading this ADR takes from `PRODUCT.md` jobs 1 and 2, but `PRODUCT.md`
  does not say this explicitly. If product wants a distinct "reactivation
  channel" concept in a later phase, that is new scope, not a bug in this
  ADR.
