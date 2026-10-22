---
description: Runs one loop iteration — next task of the active plan in docs/plans/, end to end, merged into dev
argument-hint: "[optional task id, e.g. M0.3]"
allowed-tools: Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch
---

Run **one** iteration of the development loop. `AGENTS.md` is the rule; this
file only sequences it. Task override: **$ARGUMENTS** (empty = next in plan).

## 1. Sync

- `git switch dev && git pull --ff-only`. Working tree must be clean; if not,
  stop and report what is dirty.
- The active plan is the one file in `docs/plans/` with `status: Active`
  (`docs/plans/README.md` has the rules). If it has a `[~]` task, **resume
  it** on its branch instead of picking a new one.

## 2. Pick

- The first `[ ]` task of the active plan.
- **All tasks `[x]`** → close the plan: fill *Outcome*, set `status: Done`
  and `finished`, update the index, in a `docs/close-plan-<slug>` PR. Then
  **stop: checkpoint** (see 8).
- **No active plan** and the owner approved the last checkpoint → write the
  next plan from the roadmap in `docs/plans/README.md` (`_template.md`,
  `status: Active`, `started`), in a `docs/plan-<slug>` PR. Nothing else in
  that iteration.

## 3. Branch

- `git switch -c <type>/<slug>` from `dev` (guidelines 03). Type follows what
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

## 7. Ship

- Update `docs/sdd/`, `AGENTS.md` commands and other docs the change touched.
- Mark the task `[x]` in the active plan. Note deviations (task split,
  added, reordered) as you go — they feed the plan's *Outcome*.
- Commit (Conventional Commits, no attribution trailers), push, then follow
  `.claude/commands/pr.md` to open the PR to `dev`.
- Wait for CI: `gh pr checks --watch`. Green → `gh pr merge --squash
  --delete-branch`, then `git switch dev && git pull --ff-only`. Red → back to
  6 on the same branch.
- Never merge into `main`, never force-push, never skip hooks or checks.

## 8. Report and continue

Report in **English**, in at most ten lines: task, PR link, **what actually
ran and what did not**, ADRs/DEBTs created, next task.

Then:

- **Continue** (next iteration) if the active plan still has open tasks.
- **Stop** on: plan checkpoint, `Proposed` ADR, `[!]` task, missing
  tool/credential, or an empty roadmap. When running under `/loop`, end the
  loop instead of scheduling another wakeup.

The **last line** of the report is exactly `LOOP: CONTINUE` or
`LOOP: STOP <reason>` — `scripts/loop.sh` reads it to decide whether to start
the next session.
