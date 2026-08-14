# MRROrigin UI design system

## Purpose

This document is the visual and interaction contract for `apps/web` from onboarding through the V1 analytics surfaces. It keeps issues #21-#26 coherent while preserving the behavior defined by `PRODUCT.md`, `ARCHITECTURE.md`, accepted ADRs, and the active GitHub issue.

MRROrigin should help a Stripe SaaS founder answer one core question:

> Where did my MRR come from, which acquisition sources produce revenue that survives, and what needs my attention?

If design guidance conflicts with product, architecture, backend state, or issue scope, those contracts win.

## Design workflow

Claude Code should use the project-enabled **UI UX Pro Max** plugin before implementing significant UI. Use it to challenge hierarchy, dashboard pattern, information density, chart choice, accessibility, responsive behavior, and common UI anti-patterns.

UI UX Pro Max is advisory. It must not invent metrics, backend states, workflows, dependencies, or product scope.

For a significant screen or flow:

1. Read `PRODUCT.md`, `ARCHITECTURE.md`, the issue, and this file.
2. Inspect the real API and state contracts the screen consumes.
3. Use UI UX Pro Max to challenge the proposed UX and visual hierarchy.
4. Implement the smallest coherent flow that satisfies the issue.
5. Verify phone, tablet, and desktop behavior.
6. Verify keyboard navigation, focus, labels, loading, empty, error, and recovery states.
7. Record any new reusable primitive or design-system decision in the PR.

Do not accept a visually attractive suggestion if it weakens traceability, hides degraded states, invents data, or makes revenue evidence harder to understand.

## Product character

MRROrigin should feel:

- professional and trustworthy;
- calm rather than flashy;
- premium B2B SaaS, not enterprise bureaucracy;
- data-first and evidence-first;
- compact enough for serious analysis without becoming cramped;
- fast to scan;
- confident without pretending missing data is known.

Avoid:

- crypto or trading-terminal aesthetics;
- neon palettes;
- decorative gradients as a primary visual device;
- glassmorphism as a default surface treatment;
- heavy shadows;
- oversized rounded cards everywhere;
- animation that delays reading;
- generic AI-purple styling;
- dashboards made of unrelated KPI tiles;
- charts added only because they look impressive.

## Core user journey

The product should feel like one continuous journey even though implementation is split across multiple issues.

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

Keep primary navigation small.

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

`Data health` owns tracker verification, Stripe health, reconciliation warnings, attribution coverage warnings, and entry points into unattributed revenue.

Do not create a permanent navigation item for every drill-down. Source, campaign, landing page, and customer form a drill-down path, not four separate top-level destinations.

## Layout

### App shell

- Use persistent left navigation on large screens.
- Use compact or collapsible navigation on medium screens.
- Use a drawer or sheet on small screens.
- Keep workspace and project context visible and easy to change.
- Let dense analysis tables use the available width.
- Avoid stretching simple content edge to edge on wide screens.

### Analytics page hierarchy

A typical analytics page should present:

1. Page title and concise context.
2. Date, project, and filter controls.
3. The primary answer or decision signal.
4. Supporting comparison or visualization.
5. Drill-down table or evidence.
6. Data-quality caveats and recovery actions when relevant.

### Setup page hierarchy

A setup screen should present:

1. Current step and overall progress.
2. One primary action.
3. Exact backend status.
4. Recovery guidance when blocked.
5. Optional technical detail below the main path.

## Visual tokens

Light mode is the V1 reference design. Dark mode is not required unless an issue explicitly includes it.

Use semantic implementation tokens rather than scattering raw values.

### Reference colors

- `bg`: `#F7F9FC` for the app background.
- `surface`: `#FFFFFF` for primary panels.
- `surface-subtle`: `#F1F5F9` for secondary or selected backgrounds.
- `text`: `#0F172A` for primary text.
- `text-muted`: `#64748B` for secondary text.
- `border`: `#E2E8F0` for default dividers and borders.
- `brand`: `#2563EB` for primary actions and selected navigation.
- `brand-hover`: `#1D4ED8` for primary-action hover.
- `positive`: `#15803D` for healthy and positive-revenue states.
- `warning`: `#B45309` for degraded and attention states.
- `danger`: `#B91C1C` for failed or destructive states.
- `info`: `#0369A1` for neutral operational information.

Color rules:

- Color must never be the only carrier of meaning.
- Churn, contraction, failure, and destructive actions use danger semantics consistently.
- Brand blue does not replace status colors.
- `Unattributed` is neutral or attention-oriented, not automatically a red failure state.
- Charts must not identify sources only by color; labels, legends, or direct values must remain sufficient.

### Typography

Use a neutral, highly legible sans-serif such as Inter, Geist, or an equivalent supported cleanly by the existing Next.js setup. Do not add a font dependency solely for novelty.

- Body text should usually be 14-16px.
- Page titles should usually be 28-32px.
- Section titles should usually be 18-22px.
- Labels and helper copy should usually be 12-14px.
- Numeric metrics should use tabular numerals when available.
- Currency and percentages must align cleanly in tables.
- Prefer weight, spacing, and hierarchy before adding more color.

### Spacing

Use a 4px base rhythm with common increments of 4, 8, 12, 16, 24, 32, and 48px.

- Prefer 16-24px internal panel padding.
- Prefer 24-32px between major page sections.
- Tables may be denser than cards.
- Avoid large dead zones that push evidence below the fold.

### Radius and elevation

- Controls should usually use a 6-8px radius.
- Panels should usually use an 8-12px radius.
- Pills and badges may be fully rounded when semantically appropriate.
- Default panels should rely on border and surface contrast rather than large shadows.
- Use stronger elevation only for transient layers such as menus, popovers, dialogs, and sheets.

## Core components

Build a small reusable component system instead of hand-styling each page.

Expected primitives include:

- primary, secondary, ghost, and destructive buttons;
- text input, search, select or combobox, and date-range controls;
- tabs or segmented controls for closely related views;
- status badges;
- tooltips and popovers;
- alerts and callouts;
- progress and stepper components;
- metric blocks;
- table and data-grid primitives;
- loading skeletons;
- empty states;
- error and retry states;
- drawers or sheets;
- dialogs for decisions that should interrupt the workflow;
- chart containers with consistent title, subtitle, legend, tooltip, empty state, and data caveats.

Do not create a new visual primitive when an existing one can express the same interaction.

## Onboarding and integration health (#21)

A founder should always know both where they are and what is blocking them.

Recommended flow:

```text
1. Create project
2. Install tracker
3. Verify tracking
4. Connect Stripe
5. Initial sync
6. Ready
```

The stepper represents progress. It should not imply that unavailable future steps can already be completed.

### Tracker installation

Show:

- project name and domain;
- the installation snippet or public key only according to the real backend contract;
- allowed-domain configuration;
- one clear CTA to begin verification;
- framework-specific help only when useful, preferably collapsible.

Do not make browser developer tools a prerequisite for successful setup.

### Live verification

Map UI directly to real backend states.

- `NO_TRAFFIC`: waiting state with neutral guidance.
- `BLOCKED_ORIGIN`: warning with a clear domain or origin recovery action when safe context is available.
- `INVALID_KEY`: configuration error without exposing a secret.
- `INVALID_PAYLOAD`: implementation error with practical recovery guidance.
- `RECEIVING`: positive confirmation that tracking is working.

Lead with a plain-language explanation. Keep the technical reason code available as secondary detail.

### Stripe

Connection and synchronization are separate concepts.

Show:

- connected versus not connected;
- initial sync or backfill progress;
- healthy, stale, or degraded state;
- failed webhook or reconciliation warnings when relevant;
- the recovery action beside the condition that needs it.

Do not show a reassuring green `Connected` state while a meaningful sync failure is hidden elsewhere.

## Overview (#22)

The overview must answer the founder's main question before presenting secondary analytics.

Prioritize:

- current MRR;
- New MRR for the selected period;
- Churned MRR for the selected period;
- attribution coverage;
- one compact data-health or action indicator;
- where New MRR came from;
- which sources retain best;
- what deserves investigation.

Prefer one strong source comparison and one useful visualization over many independent KPI cards.

Where supported by the relevant issue, aggregated attribution numbers should drill toward customers and evidence.

## Sources (#23)

The primary comparison dimensions are:

- customers acquired;
- New MRR;
- 30, 60, and 90-day retained MRR when available;
- NRR when available;
- attribution coverage or quality context when useful.

Use this drill-down hierarchy:

```text
Source -> campaign -> landing page -> customers
```

Traffic volume is not the hero metric. The product is about subscription revenue quality.

Use tables for precise comparison and charts for pattern recognition. Prefer sorted bars or tables over pie charts for multi-source revenue comparisons.

## Customers and evidence (#24)

The customer screen is a trust surface, not only a profile page.

Show:

- safe Stripe customer and subscription context;
- current MRR and status;
- first-touch and last-touch result;
- confidence or evidence tier;
- model version;
- chronological evidence timeline;
- repair and audit history when relevant;
- an expandable `Why this attribution?` explanation.

The founder should be able to trace an attributed value to the evidence that produced it. Never hide `Unattributed` behind an inferred fallback.

## Retention (#25)

Retention should communicate revenue durability, not merely logo retention.

Preferred views are:

- a retained-MRR cohort heatmap for 30, 60, and 90-day ages;
- source comparison;
- an optional retention curve only when it adds a distinct answer;
- NRR beside retained MRR where meaningful.

Cohort cells require accessible numeric labels. Color intensity alone is not enough.

## Weekly summary and export (#26)

The weekly experience should prioritize a small number of decision-worthy changes:

- New MRR movement;
- best and worst retained-MRR source movement;
- attribution or data-health warnings;
- unresolved unattributed revenue;
- direct links back to the supporting screen.

Do not create a long generic analytics digest.

## Data health and unattributed repair

Treat missing evidence honestly.

The basic row or detail flow should be:

```text
Stripe customer -> New MRR -> deterministic reason -> available action
```

Humanize backend reason codes while preserving them for technical inspection.

Examples:

- `NO_ACTIVE_LINK`: "No application user is linked to this Stripe customer."
- `NO_ELIGIBLE_TOUCHPOINT`: "The user is linked, but no eligible acquisition touchpoint exists in the attribution window."
- `NOT_RECALCULATED`: "Attribution has not been recalculated for the current model yet."

Never visually imply a probabilistic repair. If no deterministic repair exists, say so plainly instead of presenting a low-confidence guess.

## Tables

Tables are a primary product surface and should be treated as first-class UI.

- Use a sticky header for long desktop tables when useful.
- Right-align currency, percentages, and numeric counts.
- Left-align source and customer labels.
- Use tabular numerals.
- Make sort state visible.
- Make active filter state obvious.
- Explain empty states and the next useful action.
- Do not blindly compress 8-10 columns onto a phone.
- On small screens, prioritize columns, allow deliberate horizontal inspection, or use a row-detail presentation.
- Do not hide important values in hover-only interactions.

## Charts

Every chart must answer a named product question.

Examples include:

- "Which acquisition sources generated the most New MRR?"
- "Which source cohorts retain revenue after 90 days?"

Rules:

- Use bars for ranked comparisons.
- Use lines for time trends.
- Use cohort heatmaps for retention matrices.
- Prefer tables when precise values matter more than shape.
- Avoid 3D charts, gauges, decorative donuts, and unnecessary dual axes.
- Treat tooltips as supplemental, not the only way to access essential values.
- Render a useful empty or insufficient-data state instead of meaningless axes.

## States and feedback

Every data-fetching surface must define:

- loading;
- empty;
- success;
- stale or degraded when the backend reports it;
- failure;
- retry or recovery.

Do not replace a known degraded backend state with a generic success screen because some data happens to exist.

For mutations:

- guard accidental duplicate submits where appropriate;
- show deterministic pending, success, and error feedback;
- keep retry safe when the backend contract is idempotent;
- avoid optimistic success for security or integration operations unless the API contract supports it.

## Accessibility

Minimum UI gate:

- WCAG AA contrast for text and controls;
- full keyboard reachability;
- visible focus indication;
- semantic headings;
- explicit form labels;
- validation associated with the relevant field;
- status not communicated only through color;
- textual or numeric equivalents for charts when necessary;
- correct focus management for dialogs and sheets;
- reduced-motion preference respected;
- comfortable touch targets on mobile.

## Responsive behavior

Test approximately at:

- 375px phone;
- 768px tablet;
- 1280px desktop;
- 1440px wide desktop.

Do not design desktop first and merely stack everything on mobile. Preserve the user's task.

- Onboarding remains focused and readable.
- Project and navigation access remains obvious.
- Status and recovery actions remain adjacent.
- Complex tables degrade deliberately.
- Charts stay legible or switch to a simpler presentation.

## Motion

Motion should explain state changes, not decorate the product.

- Prefer roughly 120-200ms transitions for menus, selection, and disclosure.
- Avoid entrance animations on every dashboard card.
- Never delay numbers so an animation can finish.
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

Lead with the human explanation. Keep backend status or reason codes available as secondary technical detail when useful.

## Frontend dependencies

The current `apps/web` baseline is intentionally small. Do not assume Tailwind, shadcn/ui, a chart library, or an icon library exists unless `package.json` says so.

A UI issue may introduce a focused dependency when it materially improves consistency, accessibility, or maintainability. The PR must explain why it is needed and avoid overlapping libraries that solve the same problem.

Prefer one coherent component system over assembling many unrelated UI packages.

## Review checklist

Before a UI PR is ready for merge, verify:

- Does the screen answer the issue's actual founder job?
- Are displayed states grounded in real backend contracts?
- Does it follow this design system and existing reusable primitives?
- Was UI UX Pro Max used to challenge design rather than invent behavior?
- Is the most important action or answer visually dominant?
- Are `Unattributed`, degraded, stale, and failed states honest and actionable?
- Are loading, empty, error, and retry states implemented?
- Is keyboard and focus behavior correct?
- Does it work at phone, tablet, and desktop widths?
- Are tables and charts readable and accessible?
- Are money and percentage values formatted consistently?
- Did the PR avoid unnecessary dependencies and one-off styling?
- Does drill-down preserve evidence and traceability rather than ending at a decorative aggregate?
