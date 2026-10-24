---
description: Runs one loop iteration — next task of the active plan in docs/plans/, end to end, merged into dev
argument-hint: "[optional task id, e.g. M0.3]"
allowed-tools: Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch, Skill
---

Run **one** iteration of the development loop. `AGENTS.md` is the rule; this
file only sequences it. Task override: **$ARGUMENTS** (empty = next in plan).

The loop may run in its own worktree, where `dev` is checked out elsewhere:
always work from `origin/dev`, never `git switch dev`.

## 1. Sync and resume

- `git fetch origin --prune`.
- **On a work branch** (not detached HEAD) whose PR is not merged → that is
  the task in progress: **resume it**. A dirty tree here is this task's WIP;
  keep it. If its PR is open with red checks, go to 6; pending checks, go to 7.
- **Otherwise** `git switch --detach origin/dev`. The tree must be clean; if
  not, stop and report what is dirty.
- The active plan is the one file in `docs/plans/` with `status: Active`
  (`docs/plans/README.md` has the rules).

## 2. Pick

- The first `[ ]` task of the active plan.
- **All tasks `[x]`** → checkpoint review, then close the plan (ADR-0016):
  1. Count the review rounds already in the plan (`### … milestone review`
     headings). **Three or more** → skip to 4.
  2. Review with the `code-review` skill. Round 1: the diff from the commit
     that added the plan
     (`git log --diff-filter=A --format=%H -1 origin/dev -- <plan file>`) to
     `origin/dev`. Later rounds: only from the end commit of the previous
     round (its heading says `code-review of A..B`; use `B`) to `origin/dev`.
  3. Triage. **Only correctness findings** — wrong result, crash, hang, leak,
     race, data loss, security/privacy, each with a concrete failure
     scenario — become tasks (`MN.R1`, `MN.R2`…, with an Accept line).
     Everything else (style, comment length, naming, duplication) goes into
     **one** DEBT for the round (trigger: next PR that touches the file) or
     is rejected with a reason. If tasks were added, merge that plan change
     and continue with them — the plan is not done yet.
  4. No correctness finding, or three rounds done → fill *Outcome* (review
     findings included, open ones as DEBTs), set `status: Done` and
     `finished`, update the index, open a `docs/close-plan-<slug>` PR
     (ADR-0019):
     - *Outcome* lists a correctness finding left open, or
       `echo "${PLAN_CHECKPOINT:-}"` prints `1` → **do not merge it**: the
       owner merging it is the approval. **Stop: checkpoint.**
     - Otherwise merge it as in 7 and **continue**: the next iteration
       writes the next plan.
- **No active plan**:
  - Close-plan PR still open → **stop**: waiting for the owner.
  - Last plan `Done` on `origin/dev` (the owner merged it) → write the next
    plan from the roadmap in `docs/plans/README.md` (`_template.md`,
    `status: Active`, `started`), remove its section from the roadmap, open a
    `docs/plan-<slug>` PR, merge it as in 7. Nothing else in that iteration.

## 3. Branch

- `git switch -c <type>/<slug> origin/dev` (guidelines 03). Type follows what
  the task delivers (`feat`, `build`, `ci`, `docs`…).
- Mark the task `[~]` (`<branch>`) in the active plan.

## 4. Understand

- Read the SDD chapters, guidelines and ADRs that touch the task.
- Check the real APIs (docs, source, `--help`) of every library you use.
  Versions come from the official source, never from memory.

## 5. Build

- Implement the smallest change that satisfies the Accept line.
- Technical decision inside the architecture → ADR `Accepted` in this PR.
  Decision in the four "stop and ask" areas of `AGENTS.md` → ADR `Proposed`,
  mark the task `[!]`, open the PR as draft, **stop**.
- Something left out on purpose → `docs/tech-debt/` item with a trigger.

## 6. Verify

- Run the Accept command and `make check`. Fix and rerun.
- **Same failure three times in a row** → mark `[!]` with a DEBT id, commit
  what is useful, open a draft PR explaining the wall, **stop**.
- Missing tool, credential or device → **stop** and say exactly what is
  needed.
- **Self-check before committing** — answer each in one line, and fix what
  fails:
  1. Does every ADR decision match the evidence it cites? (e.g. "supported up
     to X" and the choice is X, or the gap is stated and justified)
  2. Does `make check` or CI get noticeably slower or costlier? Say by how
     much.
  3. Does anything in "did not run" need a DEBT or a plan task?
  4. Does the change work on a clean machine, without this machine's local
     files or env (`local.properties`, `settings.local.json`)?
  5. Is anything public that should be `internal`?
  6. `make debt-check` (ADR-0051): for each DEBT it lists, pay it in this
     PR, re-trigger it (new trigger, reason in the DEBT), or say why it did
     not fire. Event triggers it cannot see are still yours to notice.

## 7. Ship

- Update `docs/sdd/`, `AGENTS.md` commands and other docs the change touched.
- Mark the task `[x]` in the active plan. Note deviations (task split,
  added, reordered) as you go — they feed the plan's *Outcome*.
- Commit (Conventional Commits, no attribution trailers), push, then follow
  `.claude/commands/pr.md` to open the PR to `dev`.
- Wait for CI: `gh pr checks --watch`. Green → `gh pr merge --squash
  --delete-branch`, then `git fetch origin --prune && git switch --detach
  origin/dev` and delete the local branch. Red → back to 6 on the same
  branch.
- Run the merge as **its own command**: nothing chained with `;`, `&&` or
  `|`. A denial is evaluated on the whole command line, so a cleanup step
  glued to it (e.g. `rm -rf /tmp/…` hits the `rm -rf /*` deny rule) blocks
  the merge too. Denied anyway → retry the bare merge once before stopping.
- Scratch files go under `build/` in the worktree, never `/tmp`, so cleaning
  them up never needs `rm -rf /…`.
- Never merge into `main`, never force-push, never skip hooks or checks.

## 8. Report and continue

Report in **English**, in at most ten lines: task, PR link, **what actually
ran and what did not**, self-check answers that found something, ADRs/DEBTs
created, next task.

Then:

- **Continue** (next iteration) if the active plan still has open tasks, or
  the plan was just closed and merged, or the next plan was just written.
- **Stop** on: plan checkpoint, waiting for the owner, `Proposed` ADR, `[!]`
  task, missing tool/credential, or an empty roadmap. When running under
  `/loop`, end the loop instead of scheduling another wakeup.

The **last line** of the report is exactly `LOOP: CONTINUE` or
`LOOP: STOP <reason>` — `scripts/loop.sh` reads it to decide whether to start
the next session.
