---
id: ADR-0094
title: The loop reviews a milestone once, merges on auto, plans fewer tasks and stops less
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0094 — The loop reviews a milestone once, merges on auto, plans fewer tasks and stops less

## Context

The owner finds the loop slower than a normal flow. Measured on 2026-10-25:

- **Review rounds double a milestone.** M5 (PLAN-0006) shipped its tasks in
  PRs #172–#182 (20:02–22:34 UTC); its three review rounds then took PRs
  #183–#196 (22:44–00:50 UTC, 14 PRs), chasing races in `Storage.close`,
  the handshake and lost disconnects. M6 (PLAN-0007) repeated it: PRs
  #208–#215 come from its reviews, and round 3 found 8 issues, none a bug
  the app can hit (report `.loop/20261025-005412-17.md`).
- **Waiting for CI is ~37% of an iteration.** Over the last 26 iterations
  in `.loop/`, `gh pr checks --watch` took 86 of 230 minutes; local builds
  and tests 28. Docs-only PRs (review findings, plan close, ADR accepts)
  wait for the same `check` job (~3 min) as code.
- **Plans of ten tasks** include a "design on paper" and a "docs sync" task,
  each one more PR, although every PR already updates the docs it touches.
- **Stops wait for the owner.** `MAX_ITERATIONS=20` stopped a loop with work
  left (`loop: reached MAX_ITERATIONS=20`, 2026-10-24 21:11). `Proposed`
  ADRs stop the loop even when every recommendation follows a position the
  owner already took (ADR-0089: no extra masking, as ADR-0034 and ADR-0081).

ADR-0016 already capped the review at three rounds; ADR-0019 already lets
the loop merge the close-plan PR.

## Options

1. **Keep the rules** — the loop stays correct and slow.
2. **Drop the milestone review and CI waits** — fastest; loses the round
   that finds real bugs and the Linux run that catches what macOS does not
   (DEBT-0070 failed on ubuntu only).
3. **Keep each gate, make it cheaper** — one review round with a user-facing
   bar, CI that skips `check` on docs-only changes and merges on its own,
   smaller plans, fewer stops.

## Decision

Option 3. Supersedes ADR-0016.

- **A. One review round per milestone.** `code-review` at `medium` on the
  milestone diff. A finding becomes an `MN.R*` task only when **normal use**
  hits it — wrong data shown or stored, crash, hang, data loss, or a
  security or privacy issue — with a concrete failure scenario. Races that
  need rare timing, shutdown corner cases and everything else go into one
  DEBT for the round or are rejected with a reason. No second round: the
  plan closes when the round's tasks are done.
- **B. Process changes ride along.** The review's findings are committed in
  the PR of the first `MN.R*` task, and the plan's close in the PR that
  marks its last task `[x]` after the review; a docs-only PR is opened only
  when there is no task PR to carry it.
- **C. CI does not hold the loop.**
  - The `check` job skips its build when the PR changes only `docs/**` and
    `*.md` files and reports success, so it can be required.
  - `check` joins `conventions` and `docs` as required checks on `dev`, and
    the repository allows auto-merge. The loop runs
    `gh pr merge --auto --squash` and ends the iteration. The next iteration
    reads for its task while that PR's CI runs, waits for the merge before
    branching, and resumes the PR when CI is red.
- **D. Plans of four to six tasks.** No standalone "design on paper" or
  "docs sync" task: a design lands with the first task that needs it, and
  each PR keeps the docs it touches current.
- **E. Fewer stops.**
  - `MAX_ITERATIONS` defaults to 50.
  - A "stop and ask" decision on **public API** or **security / privacy**
    whose every recommended option follows a standing owner decision listed
    in `AGENTS.md` is `Accepted` by the agent, citing it; the PR says so and
    the loop continues. Product scope and licensing / cost always stop.

## Consequences

- A correctness bug that needs rare timing ships as a DEBT, not a fix; the
  owner sees it in *Outcome* at the checkpoint.
- A Linux-only failure is found one iteration later, by the next iteration
  waiting on the merge, instead of in the same one.
- The `dev` ruleset and the auto-merge setting are repository settings, kept
  in `.github/rulesets/protect-dev.json` and applied with `gh api`.
- The owner can undo a standing-decision accept in the PR review; a new
  standing decision is added to `AGENTS.md` by the owner, not the agent.
