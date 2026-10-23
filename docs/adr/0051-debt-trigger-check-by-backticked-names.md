---
id: ADR-0051
title: Open DEBT triggers are matched against changed files by their backticked names
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0051 — Open DEBT triggers are matched against changed files by their backticked names

## Context

PLAN-0003's *Lessons*: a DEBT trigger of "the next PR that touches X" was
missed by the very next fix PR. DEBT-0043's *Trigger* records it: #114 touched
`Masking.swift` and left the items unpaid, and only the third milestone review
noticed. Triggers are free text (`docs/tech-debt/_template.md`); several of
the open ones name a file (DEBT-0031, 0033, 0042, 0043, 0044 list files in
their *Trigger* section, DEBT-0026 names `gradle/libs.versions.toml`, DEBT-0027
names `MonitorClient`). The others are events no script can see ("a developer
reports…", "the first release PR").

## Options

1. **A rule in `/next` only** — the agent reads the 30-odd open DEBTs before
   each commit. Costs context every iteration and is exactly the step that
   was skipped.
2. **A script that lists candidates** — reads each `Open` DEBT's frontmatter
   `trigger` and its `## Trigger` section, takes the backticked names, and
   prints the DEBTs whose names match a file the branch changes. The agent
   still decides paid, re-triggered or not fired.
3. **A CI check that fails the PR** — a matched trigger is not always fired
   (DEBT-0027's "or its stop path" is a judgement), so a hard gate would need
   an override mechanism.

## Decision

Option 2, `scripts/debt_check.sh [base]` (`make debt-check`). A backticked
name with a `/` matches a changed path equal to it, under it, or ending in
it; without a `/` it matches the basename or the basename without its last
extension. Names with a space are commands (`make check`) and are skipped.
Changed files are the commits since the merge base with `origin/dev` plus
staged, unstaged and untracked work, so it runs before the commit. It always
exits 0; `/next` runs it in its self-check and every PR template has a
*DEBT triggers* section saying how each listed DEBT was handled.

## Consequences

- A file-named trigger can no longer be missed silently: the PR body has to
  answer it.
- Event triggers are still the agent's to notice; the script says nothing
  about them.
- A trigger that names a file without backticks is invisible to the script;
  the `## Trigger` section of new DEBTs should backtick file names.
- `make scripts-test` grows by `test_debt_check.sh`, under a second.
