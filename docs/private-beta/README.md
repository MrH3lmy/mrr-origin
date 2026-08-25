# Private beta operating framework (#29)

## Purpose and evidence rules

This runbook makes the 5–10-founder private beta repeatable without adding product telemetry or an
operator console. It is an execution aid, not beta evidence and not a launch decision. Issue #29
remains open until real workspaces meet its acceptance criteria and `PRODUCT.md` and `ROADMAP.md`
are updated from that evidence.

Record only observed facts or a founder's own words. Use `not observed`, `not supplied`, or
`not applicable` rather than estimating. Never infer willingness to pay from activity. Do not put
credentials, webhook bodies, raw email addresses, payment details, or customer-identifying data in
these files or GitHub issues. Store a workspace UUID and a separately controlled founder alias.

## Before recruitment

The beta operator must complete these gates before inviting a founder:

1. Confirm the private-beta security checklist is current, including repository-admin settings,
   production secret storage, and production identity-provider decisions.
2. Confirm the production environment exposes the documented metrics and alerts, and that the
   operator can follow the observability and recovery runbooks.
3. Run `cd apps/api && mvn verify` and `pnpm check` against the release candidate.
4. Create a blank evidence record from `workspace-evidence-template.md`; assign a non-identifying
   participant alias and workspace UUID.
5. Choose **guided** or **self-serve** before onboarding begins. Do not reclassify a guided attempt
   as self-serve after the founder needs help.
6. Define the observation window and follow-up dates for that workspace before starting. The length
   is a **decision required**; retain the same window where possible and report exceptions.

Before the first founder invitation, run the
[pre-beta production-like smoke test](pre-beta-smoke-test.md) against one exact release SHA and post
its READY / NOT READY verdict on issue #29. The smoke-test workspace is operational evidence only and
never counts toward the founder cohort.

## A. Cohort definition

### Qualifies

- A technical or semi-technical founder operating a web-based SaaS on Stripe Billing.
- Can authorize the relevant Stripe workspace and install or direct installation of a first-party
  browser tracker and deterministic `identify(externalUserId)` link.
- Has real subscription history or live acquisition activity sufficient to inspect at least one of
  MRR movement, source attribution, or retention. Record which is available; do not promise every
  report will be populated.
- Is willing to use real business data under the beta terms, attempt setup, inspect results, and
  participate in follow-up.
- Represents one SaaS business and one relevant workspace; cohort counts are founder/workspace
  counts, not seats or interviews.

Relevant Stripe/SaaS characteristics to record, not use as invented scoring criteria: test or live
mode, subscription count band supplied by the founder, history span, billing intervals and
currencies, trials/discounts/usage-based or otherwise unsupported price shapes, acquisition volume,
website stack, ability to deploy the tracker, identity-link approach, and whether source/UTM data is
already used. Record categories, not payment or customer details.

### Does not qualify for this cohort

- Ecommerce, mobile-only, enterprise sales-led attribution, or an agency evaluating unrelated
  clients.
- A business not using Stripe Billing, or seeking another billing provider.
- A prospect requiring probabilistic fingerprinting, arbitrary multi-touch models, general product
  analytics, replay, ad automation, a warehouse/CRM integration, or a native mobile dashboard.
- Anyone unable to authorize Stripe or implement deterministic identity linkage, or unwilling to
  permit evaluation using real data.
- Internal/demo/test workspaces. They may validate operations but never count toward recruited,
  onboarded, engaged, returned, decision-change, or willingness-to-pay totals.

## B. Guided onboarding checklist

The operator observes and timestamps; the founder drives unless an intervention is recorded.

- [ ] Record consent/terms confirmation, mode, `started_at`, and the founder's intended question.
- [ ] Founder signs in, creates/selects the workspace and project, and reaches tracker setup.
- [ ] Founder configures allowed domain, installs the current tracker key, deploys it, and triggers a
      real pageview. Record verification result and every failed attempt.
- [ ] Founder implements/confirms `identify(externalUserId)` and its deterministic Stripe-customer
      linking path. Record missing or unsupported identity cases.
- [ ] Founder starts Stripe OAuth, verifies the expected account and mode, authorizes read-only
      access, and returns to MRROrigin. Timestamp the first verified connected state.
- [ ] Observe initial backfill to completion. Record phase/status, elapsed time, retries, recovery,
      and operator actions; never expose or copy Stripe credentials.
- [ ] Confirm ingestion/readiness: tracker receipt, Stripe health, backfill completion, webhook or
      reconciliation warnings, attribution numerator/denominator, exclusions, unsupported data, and
      unattributed cases.
- [ ] Ask the founder to inspect Overview, Sources, Retention, Customers/evidence, and Data health as
      applicable. Timestamp the first useful answer or record that none was reached.
- [ ] Ask the founder to explain the answer in their own words. Record confusion/correctness reports
      and the exact product state used.
- [ ] Schedule follow-up; give recovery/support route and explain what information must not be sent.
- [ ] Complete the evidence record immediately, distinguishing observation from founder report.

`connected_at - started_at` measures time to connected Stripe workspace. Also preserve milestone
timestamps so tracker work, OAuth, and waiting are visible rather than collapsed into one duration.
Operator intervention includes instructions beyond the normal UI/setup documentation, direct
configuration/code changes, data repair/replay/retry, or manual diagnosis; record each separately.

## C. Self-serve onboarding checklist

The founder receives only the normal product and setup documentation plus the standard support
route. Unsolicited operator guidance invalidates self-serve classification and must be recorded.

- [ ] Operator records invitation time, observation window, and blank evidence record.
- [ ] Founder records/communicates start time and intended question before setup.
- [ ] Founder creates/selects workspace/project; configures allowed domain; installs, deploys, and
      verifies the tracker; implements/confirms deterministic identify/linking; connects the intended
      Stripe account; and waits for backfill using only normal product guidance.
- [ ] Founder confirms the data-health states and attempts to answer the intended question on the
      relevant reporting surfaces.
- [ ] Founder uses the standard support route for blockers. Operator timestamps the request,
      classifies it, records resolution/recovery, and does not silently fix it.
- [ ] Founder supplies the first-use and follow-up evidence; operator captures system-observable
      states and marks unavailable fields `not observed`.
- [ ] Operator conducts the neutral interview and records whether/what the founder returned to.

## E. Founder interview guide

Ask consistently; follow up with “What makes you say that?” or “Can you show me?” without suggesting
a preferred answer.

### Before use

1. What were you hoping to learn or decide today?
2. How do you answer that question now?
3. Which parts of your subscription and acquisition setup do you expect may matter here?

### After setup and first inspection

1. Please describe what you did from invitation to this point.
2. Where, if anywhere, did you stop, retry, seek help, or feel unsure?
3. What do you believe the current data-health and coverage states mean?
4. What question, if any, can you answer with the product now? Please show how you reached it.
5. What result was missing, unsupported, incorrect, or difficult to interpret?
6. How confident are you in the answer, and what evidence would change that confidence?
7. Did you make or plan any marketing, product, or business decision after seeing this? What was the
   decision, and what role did this information play? “No change” is a valid answer.

### Follow-up and repeat use

1. Since the last session, did you return? If so, what prompted the return and what did you inspect?
2. How, if at all, did this fit into an existing recurring workflow?
3. What did you use instead when this product did not answer a question?
4. What would need to change for you to continue using it?

### Willingness to pay

1. Would you consider paying for this product in its current state? Why or why not?
2. If you have a price or range in mind, what is it, in which currency and billing period, and what
   assumptions or limits does it include? It is fine not to name one.
3. What would you compare that purchase with?

Do not present a candidate price before collecting unaided expectations unless the session is an
explicit, separately labeled pricing experiment. Preserve “would consider,” conditional answers,
and explicit refusals; do not convert them into purchase intent.

## F. Quantitative success criteria and reporting

Always publish raw `N / denominator`, the denominator definition, missing observations, and caveats.
Do not average percentages across workspaces. Report distributions and individual durations for a
5–10-workspace cohort; do not hide failed attempts by reporting completers only.

| Measure               | Numerator / denominator                                                                                                       | Existing criterion or decision status                                           |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Recruitment           | qualified founders who consented / qualified founders invited                                                                 | Cohort target: 5–10 founders                                                    |
| Onboarding completion | workspaces reaching verified Stripe connection / onboarding attempts                                                          | **Decision required**                                                           |
| Setup time            | each attempt's start-to-connected duration; median across connected workspaces                                                | Median ≤10 minutes                                                              |
| Setup failures        | attempts with ≥1 failed step / onboarding attempts; also raw failures by step                                                 | **Decision required**                                                           |
| Operator intervention | attempts requiring ≥1 intervention / onboarding attempts; raw actions                                                         | **Decision required**                                                           |
| Meaningful data       | workspaces with completed backfill and at least one applicable, inspectable real result / onboarded workspaces                | At least 5 engaged beta workspaces must complete meaningful data evaluation     |
| Attribution coverage  | total attributed eligible new customers / total eligible new customers, plus per-workspace counts and exclusions              | ≥90% after correct installation                                                 |
| Decision time         | each observed useful-report start-to-correct-identification duration / observed applicable tasks                              | Best retained-MRR source identifiable within 30 seconds                         |
| Changed decision      | engaged founders reporting a specific real changed marketing decision / engaged founders asked                                | At least 3 of first 5 engaged beta users                                        |
| Reliability           | workspaces with failed/stuck integration, recovery, or incorrect/confusing output / onboarded workspaces; raw incidents       | **Decision required**                                                           |
| Return                | founders with an observed or directly reported return in the defined window / onboarded founders eligible for the full window | **Decision required**                                                           |
| Recurring workflow    | founders describing a specific repeated decision workflow / founders interviewed at follow-up                                 | **Decision required**                                                           |
| Willingness to pay    | founders directly expressing willingness to pay / founders asked the neutral WTP question                                     | **Decision required**; report supplied prices/ranges separately, never inferred |

“Engaged,” beyond issue #29's meaningful-data acceptance wording, and the repeat-use observation
window require product decisions before analysis. Pre-register their operational definitions on
#29; do not choose definitions after seeing outcomes.

## G. Beta issue and incident classification

Create one record per symptom. Link related records without merging distinct root causes. Severity
describes effect, not urgency or blame.

| Category             | Includes                                                                |
| -------------------- | ----------------------------------------------------------------------- |
| Onboarding           | sign-in/workspace/project/setup sequencing or documentation             |
| Stripe integration   | OAuth, account/mode mismatch, backfill, webhook, reconciliation         |
| Data correctness     | wrong MRR, movement, customer, timeline, cohort, or evidence            |
| Attribution coverage | identity link, unattributed, missing source, evidence/window limitation |
| Performance          | timeouts or report/setup latency                                        |
| UX/comprehension     | state, copy, navigation, accessibility, or interpretation confusion     |
| Reliability          | failed/stuck work, duplicate/retry/recovery, availability               |
| Product-value gap    | desired decision cannot be answered despite correct/ready data          |

- **S0 security/privacy:** suspected cross-tenant access, credential/payment/PII exposure, or data
  integrity risk. Stop affected beta activity and follow incident/security procedures.
- **S1 blocker:** cannot safely complete setup or reach trustworthy data; no acceptable workaround.
- **S2 major:** important result is wrong/unavailable or needs operator recovery; workaround exists.
- **S3 minor:** limited friction or comprehension problem that does not invalidate evaluation.

Raw incident fields: ID; opened/resolved timestamps; participant alias; workspace UUID; environment
and release SHA; category/severity; observed vs founder-reported source; affected step/surface; safe
symptom; expected/actual; reproducibility; impact on evidence validity; retries/interventions;
recovery/runbook used; owner; linked engineering issue; resolution; recurrence; founder confirmation.

## Operating cadence

- Review active records and incidents after every onboarding; do not wait for the final review.
- Escalate S0 immediately. Resolve or explicitly accept S1 before further onboarding of the same
  path. Record all retry/recovery attempts using the recovery runbook.
- At each follow-up, update return and workflow fields without overwriting prior observations.
- Review aggregate counts only from completed workspace records. Keep missing denominators visible.
- Use `final-review-template.md` only after the pre-registered observation windows end.

## I. Repository changes before beta

### Required before recruitment/start

1. **This documentation slice:** keep this runbook and the two raw templates versioned and review
   them on #29 before broad implementation. No production code, telemetry, admin tooling, or product
   scope change is required to collect the defined evidence.
2. **Production secrets decision (separate blocker issue required):** select and document a
   production KMS/secrets-manager approach in an ADR, then update the private-beta security
   checklist. This is already an open gate, not a claim that #28 is incomplete.
3. **Production identity-provider decision (separate blocker issue required):** select the OIDC
   provider/configuration, document the decision at the appropriate architecture/operations level,
   and update the security checklist.
4. **Repository security settings (repo-admin action):** enable/verify Dependabot alerts and security
   updates plus native secret-scanning push protection, recording completion on the security
   checklist. Track this as one admin blocker if it cannot be completed immediately.
5. **Release/operator verification:** verify the already-delivered #28 observability, load, and
   recovery artifacts in the actual beta environment. Create an engineering issue only for a
   concrete failed check; do not reopen technical-readiness work speculatively.

The blocker issues should state outcome and acceptance evidence, not prescribe an unreviewed vendor.
None should be folded into this documentation PR.

### Nice after beta starts (evidence-gated)

- Improve setup copy or recovery paths only from repeated recorded friction.
- Add narrowly scoped telemetry only if manual evidence is materially incomplete; specify event,
  privacy, tenant scope, retention, denominator, and decision it enables first.
- Add operator tooling only after repeated incidents show the runbooks/manual APIs are inadequate.
- Test pricing variants only with a pre-registered script and real founder responses.

Do not update `PRODUCT.md` or `ROADMAP.md` with a decision until real beta evidence supports the final
GO / ITERATE / STOP review.
