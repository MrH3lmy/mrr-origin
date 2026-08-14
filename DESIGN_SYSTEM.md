# MRROrigin UI design system

## Purpose

This document defines the visual and interaction contract for `apps/web` from onboarding through the V1 analytics surfaces. It exists to keep issues #21-#26 visually coherent while preserving the product and architecture contracts in `PRODUCT.md`, `ARCHITECTURE.md`, and the accepted ADRs.

The product promise is not "show more analytics." The interface must help a Stripe SaaS founder answer, quickly and with evidence:

> Where did my MRR come from, which acquisition sources produce revenue that survives, and what needs my attention?

`PRODUCT.md` and the active GitHub issue define product behavior. This document defines how that behavior should be presented. If they conflict, product/architecture/issue requirements win.

## Design workflow

Claude Code should use the project-enabled **UI UX Pro Max** plugin as a design-quality assistant before implementing significant UI. Use it to evaluate hierarchy, dashboard pattern, information density, chart choice, accessibility, responsive behavior, and anti-patterns.

UI UX Pro Max is advisory, not authoritative. Do not let generated design advice invent metrics, backend states, workflows, dependencies, or product scope.

For a significant screen or flow:

1. Read `PRODUCT.md`, `ARCHITECTURE.md`, the issue, and this file.
2. Inspect the real API/state contracts that the screen will consume.
3. Use UI UX Pro Max to challenge layout, hierarchy, interaction, accessibility, and visualization choices.
4. Implement the smallest coherent screen/flow that satisfies the issue.
5. Verify desktop, tablet, and mobile behavior.
6. Verify keyboard navigation, focus, labels, empty/loading/error states, and color contrast.
7. In the PR, state any new reusable UI primitive or design-system decision.

Do not accept a visually attractive suggestion if it weakens traceability, hides degraded states, invents data, or makes the founder work harder to understand revenue evidence.

## Product character

MRROrigin should feel:

- professional and trustworthy;
- calm rather than flashy;
- premium B2B SaaS, not enterprise bureaucracy;
- data-first and evidence-first;
- compact enough for serious analysis without becoming visually dense;
- fast to scan;
- confident without pretending missing data is known.

Avoid:

- crypto/trading-terminal aesthetics;
- neon palettes;
- large decorative gradients;
- glassmorphism as a default surface treatment;
- heavy shadows;
- oversized rounded cards for every block;
- animation that delays reading;
- generic "AI SaaS" purple styling;
- dashboards made of unrelated KPI tiles;
- chart decoration that does not help a decision.

## Core user journey

The interface should feel like one continuous product, even though implementation is split across issues:

```text
Sign in
  -> select/create workspace
  -> create/select project
  -> install tracker
  -> configure allowed domains
  -> live verification
  -> connect Stripe
  -> initial sync
  -> healthy project
  -> overview
  -> sources
  -> retention
  -> customers/evidence
  -> unattributed repair
  -> weekly summary/export
```

Never drop a new founder onto an empty analytics dashboard before setup is healthy enough to produce a meaningful answer.

## Navigation

Keep primary navigation intentionally small.

Recommended V1 structure:

```text
MRROrigin

Overview
Sources
Retention
Customers
Data health

---

Integrations
Settings
```

`Data health` owns tracker verification, Stripe health, webhook/reconciliation warnings, attribution coverage warnings, and unattributed-revenue entry points. It may show a count badge when action is required.

Do not create separate top-level navigation entries for every report or setup sub-step. Source -> campaign -> landing page -> customer is a drill-down path, not four permanent nav items.

## Layout

### App shell

- Persistent left navigation on large screens.
- Compact/collapsible navigation on medium screens.
- Drawer/sheet navigation on small screens.
- Main content width should favor readable analysis over edge-to-edge stretching.
- Dense tables may use the full available width.
- Keep global project/workspace context visible and easy to change without making it visually dominant.

### Page hierarchy

A typical analytics page should follow:

1. Page title + concise context.
2. Date/project/filter controls.
3. Primary answer or decision signal.
4. Supporting comparisons/visualization.
5. Drill-down table or evidence.
6. Data-quality caveats/actions when relevant.

A setup page should follow:

1. Current step and overall progress.
2. One primary action.
3. Exact status from the backend.
4. Recovery guidance when blocked.
5. Optional implementation detail collapsed below the main path.

## Visual tokens

Light mode is the V1 reference design. Dark mode is not required unless an issue explicitly includes it.

Use semantic tokens in implementation instead of scattering raw values.

### Color

Reference palette:

| Token | Value | Use |
| --- | --- | --- |
| `bg` | `#F7F9FC` | app background |
| `surface` | `#FFFFFF` | primary panels |
| `surface-subtle` | `#F1F5F9` | secondary/selected backgrounds |
| `text` | `#0F172A` | primary text |
| `text-muted` | `#64748B` | secondary text |
| `border` | `#E2E8F0` | default dividers/borders |
| `brand` | `#2563EB` | primary actions/selected navigation |
| `brand-hover` | `#1D4ED8` | primary-action hover |
| `positive` | `#15803D` | healthy/positive revenue states |
| `warning` | `#B45309` | degraded/attention states |
| `danger` | `#B91C1C` | failed/destructive states |
| `info` | `#0369A1` | neutral operational information |

Rules:

- Revenue gains may use positive color, but color must not be the only carrier of meaning.
- Churn/contraction/destructive actions use danger semantics consistently.
- Brand blue is not a substitute for status colors.
- `Unattributed` is neutral/attention-oriented, not a failure-red state by default.
- Never encode source identity only by color in charts; labels/tooltips/legends must remain sufficient.

### Typography

Preferred direction: a neutral, highly legible sans-serif such as Inter/Geist or an equivalent locally managed through the existing Next.js setup. Do not add a font dependency solely for novelty.

- Body: 14-16px depending on density/context.
- Page titles: 28-32px; avoid marketing-landing-page scale inside the product.
- Section titles: 18-22px.
- Labels/helper copy: 12-14px.
- Numeric metrics use tabular numerals when available.
- Monetary values and percentages must align cleanly in tables.
- Use font weight and spacing before adding more color.

### Spacing

Use a 4px base rhythm. Common increments: 4, 8, 12, 16, 24, 32, 48.

- Prefer 16-24px internal panel padding.
- Prefer 24-32px between major page sections.
- Tables may be denser than cards.
- Avoid large dead zones that push evidence below the fold.

### Radius and elevation

- Controls: 6-8px radius.
- Panels/cards: 8-12px radius.
- Pills/badges may be fully rounded when semantically appropriate.
- Default panels should rely on borders and subtle surface contrast, not large shadows.
- Use elevated shadows only for transient layers: popovers, menus, dialogs, sheets.

## Core components

Build/reuse a small set of primitives rather than hand-styling each screen.

Expected primitives include:

- button: primary, secondary, ghost, destructive;
- text input, search, select/combobox, date range;
- tabs/segmented control only when switching closely related views;
- status badge;
- tooltip/popover;
- alert/callout;
- progress/stepper;
- metric block;
- table/data grid primitives;
- skeleton/loading state;
- empty state;
- error/retry state;
- drawer/sheet;
- dialog only for decisions that should interrupt the workflow;
- chart container with consistent title, subtitle, legend, tooltip, empty state, and data caveat treatment.

Do not create a new visual primitive when an existing one can express the same interaction.

## Onboarding and integration health (#21)

The founder should always know both **where they are** and **what is blocking them**.

Recommended journey:

```text
1. Create project
2. Install tracker
3. Verify tracking
4. Connect Stripe
5. Initial sync
6. Ready
```

The stepper is progress, not navigation to unavailable states.

### Tracker install

Show:

- project name/domain;
- copyable installation snippet/key only according to the backend secret/public-key contract;
- allowed-domain configuration;
- clear CTA to begin verification;
- expandable framework-specific help only when useful.

Do not make browser devtools knowledge a prerequisite for successful setup.

### Live verification

Map UI directly to real backend states. At minimum present clearly different treatment for:

- `NO_TRAFFIC` — waiting, neutral;
- `BLOCKED_ORIGIN` — actionable warning with received/allowed origin context when safely available;
- `INVALID_KEY` — actionable configuration error without exposing a secret;
- `INVALID_PAYLOAD` — actionable implementation error;
- `RECEIVING` / success — positive confirmation.

Use plain-language headline first, technical reason code/details second.

### Stripe

Show connection and sync as separate concepts:

- connected vs not connected;
- initial sync/backfill progress;
- healthy vs stale vs degraded;
- failed webhook/reconciliation warning when relevant;
- recovery action next to the condition that needs it.

Do not render a green "connected" badge while a meaningful sync failure is hidden elsewhere.

## Overview (#22)

The overview must answer the founder's main question before showing secondary metrics.

Recommended top section:

- current MRR;
- New MRR in the selected period;
- Churned MRR in the selected period;
- attribution coverage;
- one compact data-health/action indicator.

Then prioritize:

1. Where New MRR came from.
2. Which sources retain best.
3. What deserves investigation.

Prefer a concise source comparison plus one strong visualization over many independent cards.

Every aggregated number that claims attribution should be drillable toward customers/evidence where the relevant product issue supports it.

## Sources (#23)

Primary comparison dimensions:

- customers acquired;
- New MRR;
- 30/60/90-day retained MRR when available;
- NRR when available;
- attribution coverage/quality context where useful.

Drill-down hierarchy:

```text
Source -> campaign -> landing page -> customers
```

Do not make traffic volume the hero metric. MRROrigin is about subscription revenue quality.

Use tables for precise comparison and charts for pattern recognition. Avoid pie charts for multi-source revenue comparison when a sorted bar/table communicates ranking better.

## Customers and evidence (#24)

This is a trust screen, not only a customer profile.

Show:

- Stripe customer/subscription summary using safe identifiers;
- current MRR/status;
- first-touch and last-touch result;
- confidence/evidence tier;
- model version;
- chronological evidence timeline;
- repair/audit history when relevant;
- an expandable "Why this attribution?" explanation.

The user should be able to trace an attributed value to the stored evidence that produced it. Never hide `Unattributed` behind an inferred fallback.

## Retention (#25)

Retention should communicate revenue durability, not merely logo retention.

Preferred views:

- retained-MRR cohort heatmap for 30/60/90-day ages;
- source comparison;
- optional retention curve only if it adds a distinct answer;
- NRR alongside retained MRR where meaningful.

Cohort cells require accessible numeric labels; color intensity alone is insufficient.

## Weekly summary/export (#26)

The weekly experience should prioritize a small number of decision-worthy changes:

- New MRR movement;
- best/worst retained-MRR source movement;
- attribution/data-health warning;
- unresolved unattributed revenue;
- direct path back into the supporting screen.

Do not generate a long generic analytics digest.

## Data health and unattributed repair

Treat missing evidence honestly.

An unattributed entry should show:

```text
Stripe customer -> New MRR -> deterministic reason -> available action
```

Reasons should be humanized while preserving the backend code for inspection, e.g.:

- `NO_ACTIVE_LINK` -> "No application user is linked to this Stripe customer."
- `NO_ELIGIBLE_TOUCHPOINT` -> "The user is linked, but no eligible acquisition touchpoint exists in the attribution window."
- `NOT_RECALCULATED` -> "Attribution has not been recalculated for the current model yet."

Never visually imply that a suggested repair is probabilistic. If no deterministic repair exists, say so plainly rather than showing a low-confidence guess.

## Tables

Tables are a primary product surface and must be treated as first-class UI.

- Sticky header for long desktop tables when useful.
- Right-align currency, percentages, and numeric counts.
- Keep source/customer labels left-aligned.
- Use tabular numerals.
- Sort state must be visible.
- Filters should summarize active state.
- Empty state explains why there are no rows and what the user can do next.
- Mobile should not blindly compress 8-10 columns. Prioritize columns, allow horizontal inspection, or switch to a deliberate row-detail layout.
- Do not hide important data in hover-only interactions.

## Charts

Every chart must answer a named product question.

Before adding one, write the question in the component/PR description, e.g. "Which acquisition sources generated the most New MRR?" or "Which source cohorts retain revenue after 90 days?"

Rules:

- Use bars for ranked comparisons.
- Use lines for time trends.
- Use cohort heatmaps for retention matrices.
- Prefer tables when precise values/comparison matter more than shape.
- Avoid 3D charts, gauges, decorative donuts, and charts with unnecessary dual axes.
- Tooltips are supplemental; essential values must remain accessible without hover.
- Provide a useful empty/insufficient-data state instead of rendering meaningless axes.

## States and feedback

Every data-fetching surface must define:

- loading;
- empty;
- success;
- stale/degraded when the backend can report it;
- failure;
- retry/recovery.

Do not replace a known degraded backend state with a generic success screen because data happens to exist.

For mutations:

- disable/guard duplicate submits where appropriate;
- show deterministic pending/success/error feedback;
- keep retry safe where the backend contract is idempotent;
- do not use optimistic success for security/integration operations unless the API contract supports it.

## Accessibility

Minimum UI gate:

- WCAG AA contrast for text and controls;
- full keyboard reachability;
- visible focus indication;
- semantic headings;
- explicit form labels;
- validation associated with the relevant field;
- status not communicated only through color;
- charts/tables have textual/numeric equivalents where necessary;
- dialogs/sheets manage focus correctly;
- reduced-motion preference respected;
- touch targets are comfortably usable on mobile.

## Responsive behavior

Reference widths should be tested approximately at:

- 375px phone;
- 768px tablet;
- 1280px desktop;
- 1440px wide desktop.

Do not design desktop first and merely stack everything on mobile. Preserve the user's task:

- onboarding remains single-goal and readable;
- project/navigation access remains obvious;
- status/recovery actions remain adjacent;
- complex analytics tables degrade deliberately;
- charts remain legible or switch to a simpler presentation.

## Motion

Motion should explain state changes, not decorate the product.

- Prefer 120-200ms transitions for menus, selection, and disclosure.
- Avoid entrance animations on every dashboard card.
- Never delay numbers appearing so an animation can complete.
- Respect `prefers-reduced-motion`.

## Copy

UI copy should be concise, specific, and operational.

Prefer:

> Tracker detected. Events are arriving from `app.example.com`.

Over:

> Awesome! Everything is looking great and your integration journey is complete!

Prefer:

> Stripe sync is stale. The oldest pending webhook is 27 hours old.

Over:

> There may be an issue with your integration.

Use backend reason/status codes internally and, when useful, in expandable technical details. Lead with the human explanation.

## Frontend dependencies

The current `apps/web` baseline is intentionally small. Do not assume Tailwind, shadcn/ui, a chart library, or an icon library exists unless `package.json` says so.

A UI issue may introduce a focused frontend dependency when it materially improves consistency, accessibility, or maintainability. The PR must explain why it is needed and avoid overlapping libraries that solve the same problem.

Prefer a small coherent component system over assembling many unrelated UI packages.

## Review checklist

Before a UI PR is ready for merge, verify:

- Does the screen answer the issue's actual founder job?
- Are all displayed states grounded in real backend contracts?
- Does it follow this design system and existing reusable primitives?
- Was UI UX Pro Max used to challenge the design, not to invent product behavior?
- Is the most important action/answer visually dominant?
- Are `Unattributed`, degraded, stale, and failed states honest and actionable?
- Are loading/empty/error/retry states implemented?
- Is keyboard/focus behavior correct?
- Does it work at phone/tablet/desktop widths?
- Are tables/charts readable and accessible?
- Are monetary/percentage values formatted consistently?
- Did the PR avoid unnecessary UI dependencies and one-off styling?
- Does drill-down preserve evidence/traceability rather than ending at a decorative aggregate?
