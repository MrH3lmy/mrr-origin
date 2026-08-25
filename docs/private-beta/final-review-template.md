# Private beta final review — `[review date]`

This is a blank decision record, not evidence. Populate it only from completed records held in the
private evidence store, using the sanitization rules in the operating framework. The public review
must contain only de-identified aggregate evidence: never include workspace UUIDs, re-identifiable
aliases, founder-level timelines or price responses, raw notes, small-cell disclosures, or links to
private records. Keep issue #29 open through architecture/product review and do not update
`PRODUCT.md` or `ROADMAP.md` until real evidence supports the accepted decision.

## Review control

- Release SHA(s), environment, beta dates:
- Pre-registered guided-stage assignment/rule and allowed standard help:
- Pre-registered transition criterion and whether/when it was met:
- Pre-registered self-serve-stage assignment/rule and allowed support:
- Pre-registered definitions (onboarded, engaged, meaningful data, observation/return window):
- Protocol deviations and missing records:
- Number of private evidence records included/excluded and sanitized reasons (no aliases):
- Private-record retention deadline and deletion status (aggregate status only):

## Cohort flow (raw counts)

| Stage                                                    |   N | Denominator and eligibility                        | Missing / caveat |
| -------------------------------------------------------- | --: | -------------------------------------------------- | ---------------- |
| Qualified invited                                        |     |                                                    |                  |
| Recruited/consented                                      |     | qualified invited                                  |                  |
| Onboarding attempted                                     |     | recruited                                          |                  |
| Onboarded/Stripe connected                               |     | attempts                                           |                  |
| Reached meaningful data                                  |     | onboarded                                          |                  |
| Engaged and completed meaningful evaluation              |     | recruited and onboarded, per registered definition |                  |
| Eligible for full return window                          |     | onboarded                                          |                  |
| Returned                                                 |     | eligible for full window                           |                  |
| Reported a specific changed decision                     |     | engaged founders asked                             |                  |
| Asked neutral WTP question                               |     | interviewed founders                               |                  |
| Expressed willingness to pay (yes; conditional separate) |     | founders asked                                     |                  |

Do not count internal/test workspaces. Explain attrition at every stage.

## Criteria evidence

### Setup/onboarding

- Individual start → connected durations; median among connected; failures excluded from the median
  but shown separately:
- Attempts with failure / attempts:
- Attempts needing operator intervention / attempts; action counts/types:
- Guided versus self-serve counts and outcomes (do not claim causal effects from this cohort):
- Evidence against/for median ≤10 minutes:

### Data readiness

- Backfills completed / onboarded; incomplete/stuck/recovered:
- Workspaces ingestion-ready where applicable / applicable workspaces:
- Total attributed eligible new customers / total eligible new customers:
- Per-workspace coverage counts, installation-correctness evidence, exclusions:
- Unsupported/missing/unattributed cases and effect on evaluation:
- Workspaces reaching meaningful data / onboarded:

### Product value

- Applicable retained-MRR tasks completed within 30 seconds / observed applicable tasks:
- Individual decision/useful-answer times and non-completions:
- Founders reporting a specific changed real marketing decision / engaged founders asked:
- Other product/business decisions (separate from the existing marketing-decision criterion):
- Questions answered, unanswered, confusing, or disputed:

### Reliability

- Workspaces with any incident / onboarded; incidents by category and severity:
- Failed/stuck integrations, retries, recoveries, incorrect output, unresolved caveats:
- Evidence invalidated or repeated after correction:

### Return signal

- Returned / eligible for the full pre-registered window:
- Return triggers and surfaces inspected:
- Founders describing a specific recurring workflow / founders interviewed at follow-up:
- No-return and missing-observation caveats:

### Willingness to pay

- Yes / asked; conditional / asked; no / asked; declined or not supplied / asked:
- Founder-supplied ranges with currency, period, conditions (no derived “average price” unless the
  sample and question were comparable):
- Pricing scripts/variants and ordering caveats:
- Explicit check that no response was inferred from usage:

## Failures and caveats

- Selection/sample bias:
- Guided-support effect:
- Observation-window differences:
- Data-volume/history/applicability limits:
- Missing evidence:
- Open S0/S1/S2 incidents:
- Results that cannot be generalized:

## Public V1 proposal (evidence-linked)

| Scope item / positioning claim | Include, change, or defer | Sanitized aggregate evidence / counts | Reason and caveat |
| ------------------------------ | ------------------------- | ------------------------------------- | ----------------- |
|                                |                           |                                       |                   |

### Proposed V1 scope

-

### Deferred scope

-

### Pricing experiment

- Hypothesis supported by direct founder evidence:
- Segment and eligibility:
- Offer/price/range, currency, period, limits:
- Neutral script and order:
- Decision measure with raw denominator:
- Duration/sample rule (**decision required**):
- Stop/safety condition:
- What this experiment will not establish:

## Decision

Select one; do not redefine criteria after seeing results.

- [ ] **GO** — evidence supports moving to the proposed public V1/pricing experiment, subject to the
      listed launch gates.
- [ ] **ITERATE** — evidence supports another bounded beta cycle; list hypotheses, changes, and
      pre-registered criteria.
- [ ] **STOP** — evidence does not support continuing this product direction; list the evidence and
      safe shutdown/data-handling steps.

- Decision rationale linked to raw evidence:
- Dissent/uncertainty:
- Required follow-up issues and owners:
- Accepted PRODUCT.md changes:
- Accepted ROADMAP.md changes:
- Reviewers and approval date:
