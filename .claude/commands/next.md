---
description: Runs one loop iteration — next task in docs/plan.md, end to end, merged into dev
argument-hint: "[optional task id, e.g. M0.3]"
allowed-tools: Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch
---

Run **one** iteration of the development loop. `AGENTS.md` is the rule; this
file only sequences it. Task override: **$ARGUMENTS** (empty = next in plan).

## 1. Sync

- `git switch dev && git pull --ff-only`. Working tree must be clean; if not,
  stop and report what is dirty.
- If `docs/plan.md` has a `[~]` task, **resume it** on its branch instead of
  picking a new one.

## 2. Pick

- The first `[ ]` task of the first milestone that still has open tasks.
- If the previous milestone just closed (all `[x]`) and the owner has not
  reviewed it in this conversation, **stop: checkpoint** (see 8).
- A `Refine` task is a `docs/` PR that breaks the milestone into tasks with
  Accept lines. Nothing else.

## 3. Branch

- `git switch -c <type>/<slug>` from `dev` (guidelines 03). Type follows what
  the task delivers (`feat`, `build`, `ci`, `docs`…).
- Mark the task `[~]` (`<branch>`) in `docs/plan.md`.

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
- Mark the task `[x]` in `docs/plan.md`.
- Commit (Conventional Commits, no attribution trailers), push, then follow
  `.claude/commands/pr.md` to open the PR to `dev`.
- Wait for CI: `gh pr checks --watch`. Green → `gh pr merge --squash
  --delete-branch`, then `git switch dev && git pull --ff-only`. Red → back to
  6 on the same branch.
- Never merge into `main`, never force-push, never skip hooks or checks.

## 8. Report and continue

Report in Portuguese, in at most ten lines: task, PR link, **what actually ran
and what did not**, ADRs/DEBTs created, next task.

Then:

- **Continue** (next iteration) if the milestone still has open tasks.
- **Stop** on: milestone checkpoint, `Proposed` ADR, `[!]` task, missing
  tool/credential, or empty plan. When running under `/loop`, end the loop
  instead of scheduling another wakeup.
