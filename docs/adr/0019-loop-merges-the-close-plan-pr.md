---
id: ADR-0019
title: The loop merges the close-plan PR and moves on to the next plan
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0019 — The loop merges the close-plan PR and moves on to the next plan

## Context

`.claude/commands/next.md` opened the `docs/close-plan-<slug>` PR **without
merging it** and stopped the loop: the owner merging it was the approval to
start the next plan. Since ADR-0016 bounds the milestone review to three
rounds, closing a plan is mechanical — it no longer depends on a judgement
call that only the owner can make:

- PLAN-0001 reached the checkpoint already past three rounds, so the
  iteration skipped the review and only wrote *Outcome* (PR #64, report
  `.loop/20261022-171547-2.md`). The owner merged it unchanged, and the next
  iteration wrote PLAN-0002 (PR #65).
- Every plan now costs one stop, one manual merge and one manual `make loop`,
  while the decisions that do need the owner — the four "stop and ask" areas
  of `AGENTS.md` — already stop the loop through `Proposed` ADRs (as
  the M1.1 decisions did in PR #66).

## Options

1. **Keep the stop** — the owner approves every milestone; the loop idles
   until the owner is back, even when there is nothing to decide.
2. **Stack the PRs** — open the close-plan PR, then branch the next plan and
   its tasks from it unmerged. Every squash merge rewrites the base of the
   PRs above it, and `/next` reads the active plan from `origin/dev`, which
   would not have it yet.
3. **Merge and continue, stop only when it matters** — the loop merges the
   close-plan PR after green CI like any task PR, and stops only when a
   correctness finding is left open or the owner asked for the stop.

## Decision

Option 3:

- The close-plan PR is merged after green CI, like a task PR; the report
  ends in `LOOP: CONTINUE` and the next iteration writes the next plan from
  the roadmap.
- The loop still **stops at the checkpoint**, without merging, when *Outcome*
  lists a correctness finding left open as a DEBT, or when the environment
  has `PLAN_CHECKPOINT=1` (`PLAN_CHECKPOINT=1 make loop`).
- `scripts/loop.sh` notifies when the active plan changes between
  iterations, so the owner learns a milestone closed without watching the
  logs.

## Consequences

- The loop runs across milestones until something needs the owner: a
  `Proposed` ADR, a `[!]` task, a missing tool, an open correctness finding
  or an empty roadmap.
- The owner reviews a closed plan's *Outcome* after the fact, in its merged
  PR; follow-up work goes in the next plan (plans are immutable once closed).
- A plan written from the roadmap is merged without the owner reading it
  first. Adding or dropping a feature there is still product scope and stops
  the loop (`docs/plans/README.md`).
