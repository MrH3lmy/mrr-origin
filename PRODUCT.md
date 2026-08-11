# Product contract

## One-sentence promise

MRROrigin helps Stripe-based SaaS founders see where their MRR originated and which acquisition sources produce revenue that survives.

## Problem

Traffic analytics explains visits. Subscription analytics explains revenue health. Founders still have to manually join those two views to answer the decision-making question:

> Which source, campaign, or landing page brought customers who continue paying?

Simple attribution products tend to stop at the first payment. Subscription products tend to start after the payment and lose the acquisition journey. MRROrigin owns the bridge between them.

## Initial customer

The initial customer is a technical or semi-technical founder who:

- runs a web-based SaaS using Stripe Billing;
- has enough acquisition activity that source decisions are no longer obvious;
- wants an answer without building a warehouse or maintaining a custom attribution pipeline; and
- values a short, auditable report over a general-purpose analytics suite.

Enterprise sales-led attribution, ecommerce, mobile-only apps, and agencies managing many unrelated clients are not the first customer.

## Jobs to be done

1. When I acquire a paying customer, show me where that customer originally came from.
2. When revenue changes, show the acquisition source behind the new, expanded, contracted, churned, or reactivated MRR.
3. When two channels appear successful, show which one produces more durable subscription revenue.
4. When attribution is missing or uncertain, show me why and let me repair the link.
5. Once a week, tell me what changed and which result deserves investigation.

## Product principles

### Revenue quality over traffic volume

The primary objects are customers, subscriptions, MRR movements, and their acquisition evidence. Pageviews exist to support attribution; they are not the product's center of gravity.

### Evidence over false precision

Every attributed result must link to the touchpoint and identity evidence that produced it. Unknown historical origin remains `Unattributed`; the system must never fabricate a source to complete a chart.

### Fast time to first answer

The target setup is one tracker installation plus Stripe authorization, completed within ten minutes.

### Focus before breadth

V1 supports one excellent Stripe workflow. Additional billing providers, mobile SDKs, replay, broad product analytics, complex multi-touch models, and AI chat are deferred until customer evidence justifies them.

### Safe recalculation

Raw immutable inputs are retained separately from derived attribution and MRR results so calculation rules can evolve without corrupting source data.

## V1 scope

### Included

- Workspaces and projects with strict tenant boundaries
- First-party browser tracker
- Landing page, referrer, UTM, session, and custom-event collection
- Anonymous visitor to known application-user identification
- Stripe connection, webhook ingestion, and initial backfill
- Customer, subscription, invoice, payment, refund, and subscription-change normalization
- New, expansion, contraction, churned, and reactivation MRR movements
- First-touch and last-touch attribution
- Source, campaign, and landing-page reports
- Customer attribution timeline and confidence level
- Unattributed revenue inbox and integration-health diagnostics
- 30/60/90-day retained-MRR cohorts
- Weekly actionable summary

### Explicitly excluded

- Session replay and heatmaps
- General product analytics
- Mobile SDKs
- Multiple billing providers
- Probabilistic fingerprinting
- Arbitrary multi-touch weighting models
- Ad-platform bid automation
- Data warehouse and enterprise CRM integrations
- Native mobile dashboard
- AI chat and MCP

## Core screens

1. **Overview** — new and retained MRR, movements, top sources, and data-health warnings.
2. **Sources** — source/campaign/page comparison across acquisition and retention metrics.
3. **Retention** — source-based 30/60/90-day revenue cohorts.
4. **Customers** — customer-level subscription and attribution evidence timeline.
5. **Data health** — tracker verification, Stripe sync status, webhook failures, and unattributed revenue.

## Metric vocabulary

| Metric               | Product meaning                                                                         |
| -------------------- | --------------------------------------------------------------------------------------- |
| New MRR              | Recurring revenue introduced by a newly active paid subscription                        |
| Expansion MRR        | Positive recurring-revenue change for an existing customer                              |
| Contraction MRR      | Negative recurring-revenue change that does not fully churn the customer                |
| Churned MRR          | Recurring revenue lost when a customer no longer has active paid MRR                    |
| Reactivation MRR     | Recurring revenue restored after the customer previously reached zero MRR               |
| Retained MRR         | MRR from an acquisition cohort still active at the selected age                         |
| NRR                  | Starting cohort MRR plus expansion, less contraction and churn, divided by starting MRR |
| Attribution coverage | Share of eligible new customers linked to acceptable acquisition evidence               |

Precise calculation rules, including trials, discounts, tax, interval normalization, pauses, delinquency, and currency conversion, require an approved architecture decision before implementation.

## V1 success criteria

- Median setup time is ten minutes or less.
- At least 90% of new Stripe customers are attributed after correct installation.
- Duplicate webhook delivery creates no duplicate billing object or MRR movement.
- A founder can identify the best retained-MRR source within 30 seconds.
- At least three of the first five engaged beta users change a real marketing decision using MRROrigin.

## Open assumptions to validate

- Founders value retained-MRR attribution enough to add a second analytics product or replace an existing one.
- First-touch and last-touch are sufficient for the initial customer segment.
- Stripe-only support is a useful narrow entry point rather than a blocking limitation.
- A transparent unattributed state increases trust more than a higher but opaque coverage percentage.
- Source-level retention becomes useful with the data volume available to smaller SaaS businesses.
