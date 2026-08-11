# Roadmap

The roadmap is ordered by dependency, not marketing excitement. GitHub issues are the executable backlog; this document protects the intended sequence and scope.

## Phase 1 — Foundation

**Outcome:** contributors can make tested, issue-scoped changes against a shared product and architecture contract.

- Product contract and exclusions
- Architecture baseline and ADR process
- Backend, dashboard, and tracker scaffolds
- Workspace/project tenancy migration
- PostgreSQL local environment
- CI for Java and TypeScript
- Contribution and AI-collaboration rules
- Prioritized GitHub backlog

## Phase 2 — Tracking and identity

**Outcome:** a customer can install the tracker, verify traffic receipt, and link an anonymous visit to an application user.

- Project ingestion keys and allowed domains
- Visitor/session/touchpoint schema
- Pageview, UTM, referrer, and custom-event ingestion
- Batching, retries, rate limits, and duplicate protection
- `identify(externalUserId)`
- Installation verifier and live diagnostic stream
- Retention and deletion policy for tracking data

## Phase 3 — Stripe and subscription ledger

**Outcome:** a workspace can connect Stripe and obtain a complete, replayable local subscription timeline.

- Stripe authorization and secret management
- Signed webhook receipt with durable raw-event storage
- Resumable initial backfill
- Customer, subscription, item, invoice, payment, refund, and discount normalization
- Provider reconciliation and data-health reporting
- Deterministic MRR policy and movement engine

## Phase 4 — Attribution

**Outcome:** every eligible MRR movement is either supported by inspectable acquisition evidence or explicitly marked unattributed.

- Stripe customer to application-user identity bridge
- First-touch and last-touch models
- Versioned evidence and confidence rules
- Unattributed revenue inbox
- Replay/recalculation tooling
- Golden attribution and MRR fixtures

## Phase 5 — Founder dashboard

**Outcome:** a founder can identify which sources create durable MRR in under 30 seconds.

- Onboarding and integration health
- Overview and MRR movement views
- Source/campaign/landing-page comparison
- Customer evidence timeline
- 30/60/90-day retention cohorts
- Export and weekly action summary

## Phase 6 — Private beta

**Outcome:** five to ten Stripe SaaS founders use MRROrigin with trustworthy data and provide decision-level feedback.

- Security and privacy review
- Tenant-isolation suite
- Load and failure-recovery tests
- Observability and operator runbooks
- Data export/deletion
- Product analytics limited to beta success metrics
- Feedback interviews and scope decision for public V1

## Release gates

| Gate              | Required evidence                                                     |
| ----------------- | --------------------------------------------------------------------- |
| Tracking alpha    | Verified install plus repeatable anonymous-to-known identity fixture  |
| Billing alpha     | Replayed Stripe fixtures produce identical normalized state           |
| Attribution alpha | Golden fixtures explain every attributed and unattributed result      |
| Private beta      | Security checklist complete and no known cross-tenant access path     |
| Public V1         | Setup, coverage, decision-time, and beta-outcome targets demonstrated |

## Not on the roadmap before private beta

- More billing providers
- Session replay or heatmaps
- Mobile SDKs
- General product analytics
- Complex multi-touch weighting
- Native mobile apps
- AI chat or MCP
- Microservices, Kafka, or a separate warehouse
