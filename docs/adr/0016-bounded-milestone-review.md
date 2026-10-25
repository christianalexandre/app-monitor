---
id: ADR-0016
title: Milestone review is incremental, keeps only correctness findings and stops after three rounds
status: Superseded
date: 2026-10-22
superseded_by: ADR-0094
---

# ADR-0016 — Milestone review is incremental, keeps only correctness findings and stops after three rounds

## Context

`.claude/commands/next.md` closed a plan only when a `code-review` of the
**whole** milestone diff returned **no findings**. PLAN-0001 (M0) shows that
this never converges:

- M0.1–M0.7 shipped in PRs #4–#11; the eleven review rounds that followed
  took PRs #12–#61 (50 PRs, tasks M0.R1–M0.R39) without closing the plan.
- Every round reviews `d8fbae9..origin/dev`, so the diff only grows and each
  fix is reviewed again next round. `AppMonitorServer` lifecycle was fixed in
  eight tasks (R6, R10, R12, R15, R20, R24, R27, R32), each round finding a
  race in the previous round's fix.
- Late rounds are mostly non-defects: a 5-line KDoc (R22, R25, R36), `--help`
  on stderr (R36), a duplicated default (R38).

A reviewer always finds something, so "no findings" is not a reachable exit.

## Options

1. **Keep the rule, raise the review threshold** — fewer findings per round,
   still no bound on rounds and still re-reviews the whole milestone.
2. **Drop the milestone review** — loses the round that found real races and
   leaks in M0 (R3, R6, R10).
3. **Incremental, correctness-only, capped** — the first round covers the
   milestone, later rounds only what changed since the previous one; only
   correctness findings become tasks; at most three rounds.

## Decision

Option 3:

- **Round 1** reviews the milestone diff (plan commit to `origin/dev`).
  **Round N > 1** reviews only from the end commit of round N−1, which each
  round heading already records (`code-review of A..B`).
- **Only correctness findings become `MN.R*` tasks**: a wrong result, crash,
  hang, leak, race, data loss or security/privacy issue, with a concrete
  failure scenario. Anything else (style, comment length, naming, duplication)
  goes into one DEBT per round, with the trigger "next PR that touches the
  file", or is rejected with a reason.
- **At most three rounds.** When the tasks of the third round are done — or
  a round yields no correctness finding — the plan closes; findings still open
  are listed in *Outcome* and recorded as DEBTs.

PLAN-0001 is already past three rounds: once its open tasks are done it goes
straight to close.

## Consequences

- The loop reaches the owner checkpoint and the next milestone instead of
  polishing the last one.
- A correctness bug found in round 3's own fixes is not chased by a round 4;
  it lands as a DEBT and in *Outcome*, where the owner sees it at the
  checkpoint.
- Style and comment-rule findings are paid when someone next touches the file,
  not as standalone PRs.
