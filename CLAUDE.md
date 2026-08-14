# Claude collaboration guide

Follow `AGENTS.md` for repository-wide engineering rules and required reading. Before accepting a task, also read the complete GitHub issue.

## Working agreement

1. Claim one issue with a comment describing your intended files and module boundary.
2. Create `claude/<issue-number>-<short-description>` from the latest `main`.
3. Check open draft PRs before changing shared configuration, migrations, or documentation.
4. Open a draft PR early and link the issue.
5. Keep the diff issue-scoped; propose follow-up work as separate issues.
6. Report validation commands and results in the PR.

When a requested implementation conflicts with the product exclusions or architecture rules, stop and raise the conflict on the issue. Do not quietly widen the scope.

## UI work

For any significant `apps/web` UI/UX task:

- Read `DESIGN_SYSTEM.md` in addition to the repository-required context and issue.
- Use the project-enabled `ui-ux-pro-max@ui-ux-pro-max-skill` plugin to challenge visual hierarchy, dashboard/layout pattern, information density, chart choice, responsiveness, accessibility, and common UI anti-patterns before implementation.
- Treat UI UX Pro Max as design guidance only. It must not invent product behavior, metrics, backend states, dependencies, or scope; `PRODUCT.md`, architecture/ADRs, the issue, and real API contracts remain authoritative.
- Prefer extending reusable project primitives over one-off page styling.
- Do not assume Tailwind, shadcn/ui, a chart library, or another UI dependency exists. Inspect `apps/web/package.json`; justify any new dependency in the PR.
- Implement and test real loading, empty, success, degraded/stale, failure, retry, keyboard/focus, and responsive states required by the screen.
- In the PR description, note the UI UX Pro Max review performed and any new reusable design-system decision.

If the plugin is unavailable in the current Claude environment, continue using `DESIGN_SYSTEM.md` and state that limitation in the PR rather than substituting a random design system.

## Communication style

Optimize replies and PR/commit text for output-token efficiency:

- Default to short, direct sentences. No preamble, no restating the request, no summarizing what a diff already shows.
- Skip narration of routine steps (reading a file, running a lint). Report findings, decisions, and results only.
- Commit messages and PR descriptions: state what changed and why in as few lines as the reviewer needs, not more.
- Never pad for tone. Terse and correct beats polished and long.
- Code, commands, error text, and identifiers are never shortened or paraphrased — only explanatory prose is trimmed.
