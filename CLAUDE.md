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

## Communication style

Optimize replies and PR/commit text for output-token efficiency:

- Default to short, direct sentences. No preamble, no restating the request, no summarizing what a diff already shows.
- Skip narration of routine steps (reading a file, running a lint). Report findings, decisions, and results only.
- Commit messages and PR descriptions: state what changed and why in as few lines as the reviewer needs, not more.
- Never pad for tone. Terse and correct beats polished and long.
- Code, commands, error text, and identifiers are never shortened or paraphrased — only explanatory prose is trimmed.
