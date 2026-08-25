# Private beta workspace evidence — `[participant alias]`

Create one private copy per real beta workspace **only in the access-controlled evidence store
defined by the operating framework**. Never commit a filled copy to this public repository or post
it to a GitHub issue or pull request. Blank fields mean “not recorded” and must be resolved to an
observed value, `not observed`, `not supplied`, or `not applicable`. Never include credentials,
webhook bodies, raw emails, payment details, customer identifiers, or interviewee names.

## Record control and cohort fit

- Participant alias:
- Workspace UUID:
- Evidence-record owner:
- Private-store access group / approval record:
- Retention deadline / deletion owner:
- Release SHA / environment:
- Guided or self-serve (chosen before start):
- Invitation / consent timestamps (UTC):
- Observation-window start/end (UTC):
- Follow-up dates (UTC):
- Qualification evidence:
- Exclusion check:
- Founder-supplied SaaS/Stripe characteristics (bands/categories only):
- Intended business question before use:

## Setup/onboarding timeline

| Milestone                             | UTC timestamp | Observed by | Outcome / safe note |
| ------------------------------------- | ------------- | ----------- | ------------------- |
| Onboarding started                    |               |             |                     |
| Workspace/project ready               |               |             |                     |
| Tracker setup started                 |               |             |                     |
| First tracker event verified          |               |             |                     |
| Identify/link path confirmed          |               |             |                     |
| Stripe OAuth started                  |               |             |                     |
| Stripe verified connected             |               |             |                     |
| Backfill started                      |               |             |                     |
| Backfill completed                    |               |             |                     |
| First useful dashboard/result started |               |             |                     |
| Useful answer reached (or none)       |               |             |                     |

- Start → connected duration (derive from timestamps):
- Setup failures, count and step IDs:
- Operator interventions, count and exact actions:
- Founder support requests and response durations:
- Abandoned/retried steps:

## Data readiness

- Connected Stripe mode/account was the intended one (yes/no/not observed):
- Connection/verification status and observed_at:
- Backfill phase, completion, observed_at, duration:
- Backfill retries/recovery/incident IDs:
- Tracker verification / last receipt state and observed_at:
- Ingestion readiness and warnings where applicable:
- Stripe health/reconciliation/webhook warnings:
- Eligible new customers (coverage denominator):
- Attributed eligible new customers (coverage numerator):
- Coverage ratio (derive, do not hand-estimate):
- Coverage exclusion reason counts:
- Unattributed/missing-source counts:
- Unsupported/missing-data cases and affected surface:
- Why data is or is not “meaningful” under the pre-registered definition:

## Product value tasks

Repeat rows for each real question. A correct “not answerable” is evidence.

| Intended question | Surface/path used | Applicable? | Start/end UTC | Answer reached? | Founder interpretation | Correctness checked/how | Decision effect |
| ----------------- | ----------------- | ----------- | ------------- | --------------- | ---------------------- | ----------------------- | --------------- |
|                   |                   |             |               |                 |                        |                         |                 |

- Best retained-MRR source task: denominator/applicability, start/end, result:
- Time to first useful dashboard/decision (derive from timestamps):
- Specific decision reportedly changed, if any (founder's account; do not infer):
- Role MRROrigin played versus other evidence:
- Incorrect/confusing/missing output reports and incident IDs:
- Trust/confidence explanation in founder's terms:

## Reliability and recovery

- Failed or stuck integrations and incident IDs:
- Retries (automatic/manual, count, outcome):
- Recovery/runbook actions and operator time:
- Data correctness incidents and whether evaluation was invalidated:
- Open caveats at end of observation window:

## Return and recurring-workflow evidence

| Return UTC/date | Observed or directly reported | Trigger | Surface/question inspected | Outcome | Part of recurring workflow? |
| --------------- | ----------------------------- | ------- | -------------------------- | ------- | --------------------------- |
|                 |                               |         |                            |         |                             |

- Eligible for full return window (yes/no and why):
- Returned within defined window (yes/no/not observed):
- Founder-described recurring decision workflow, if any:
- What founder used instead / reason for no return:

## Interview and willingness to pay

- Interview date(s), interviewer, guide sections completed:
- Notes labeled by question (paraphrase; short consented quotes only if needed):
- Asked neutral WTP question (yes/no):
- Direct WTP response (yes/no/conditional/declined/not supplied) and rationale:
- Founder-supplied amount/range, currency, billing period, assumptions/limits:
- Comparison/alternative named by founder:
- Pricing was prompted before unaided response? If yes, experiment/script and response-order caveat:
- Explicitly confirm no WTP inference was made from usage:

## Close-out

- Onboarded under aggregate definition (yes/no + evidence):
- Engaged under pre-registered definition (yes/no + evidence):
- Reached meaningful data (yes/no + evidence):
- Reported changed decision (yes/no + evidence):
- Directly expressed willingness to pay (yes/no/conditional + evidence):
- Record completeness/missing observations:
- Incident IDs / linked issues:
- Founder confirmation or correction of interpreted findings:
- Caveats for aggregate review:
- Deletion completed_at / confirmed by (complete by the registered deadline):
