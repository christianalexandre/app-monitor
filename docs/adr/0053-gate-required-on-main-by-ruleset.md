---
id: ADR-0053
title: The release gate is required on main through the existing ruleset
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0053 — The release gate is required on main through the existing ruleset

## Context

PLAN-0004 M3.2 (b): `main` should require the release gate, if the
account's GitHub plan allows it on a private repository. Already decided:
`main` only receives `dev` through a release PR (guidelines 03).

Evidence, read on 2026-10-23 with `gh api`:

- `repos/christianalexandre/app-monitor/rulesets` lists two repository
  rulesets, `protect-dev` and `protect-main`, both
  `"enforcement": "active"`, created 2026-10-21.
- `protect-main` (id 25604903) targets `refs/heads/main` with: no deletion,
  no force-push, PR required (0 approvals, merge method `merge` only),
  required status checks `conventions` and `docs` (the jobs of
  `pr-conventions.yml`), no bypass actors.
- `repos/.../rules/branches/main` returns those four rules, i.e. GitHub
  applies them to `main` today; `repos/.../branches/main/protection`
  answers 404 "Branch not protected" (no classic protection).
- `gh api user` returns `"plan": null` (the token cannot read the plan),
  and [About rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets)
  does not state in plain words which plans enforce rulesets on private
  personal repositories (it names Team and Enterprise). No release PR
  has yet been blocked by a red required check, so enforcement is
  configured but not observed.

## Options

1. **Add the gate's jobs to `protect-main`'s required status checks**
   (recommended) — one `gh api -X PUT repos/.../rulesets/25604903` with the
   current rules plus the gate's job names (ADR-0052 decides them); the
   ruleset JSON kept in the repo so it can be diffed and reapplied. M3.11
   proves enforcement with a release PR whose gate is red and whose merge
   button is blocked; if GitHub does not block it, Option 3 applies.
2. **Classic branch protection on `main`** — the older API, same effect;
   duplicates what the ruleset already does and two mechanisms then decide
   the same thing.
3. **No enforcement, a rule instead** — the loop never merges into `main`
   (already true) and the owner merges a release PR only with the gate
   green; recorded as a DEBT whose trigger is a plan change that offers
   enforcement. Costs nothing, protects nothing against a hurried click.

## Decision

Option 1, decided by the owner on 2026-10-23: the gate's jobs (ADR-0052)
join `protect-main`'s required status checks, and the ruleset JSON is kept
in the repository. M3.11 proves a red gate blocks the merge.

## Consequences

- Option 1: a gate job renamed in `release-gate.yml` must be renamed in
  the ruleset in the same PR, or every release PR waits for a check that
  never reports.
- `strict_required_status_checks_policy` stays `false`: a release PR is
  `dev` itself, always up to date with what it brings.
