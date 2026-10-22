---
id: ADR-0013
title: Loop scripts are tested by a plain bash test in make check
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0013 — Loop scripts are tested by a plain bash test in make check

## Context

The M0 review (PLAN-0001 M0.R1) found two bugs in `scripts/`:
`loop_watch.sh` exited 1 with no message on an empty `.loop/` (`ls` fails
under `set -e -o pipefail` inside the command substitution), and the
`loop.sh` watchdog kept running after being cancelled, so it sent
`kill -TERM` to the already reaped session PID and could write a spurious
`.timeout`. Both were reproduced on `origin/dev` before the fix (a cancelled
watchdog killed a live target and touched the marker). Nothing tested the
scripts, and `run_session` hard-coded the `claude` command.

## Options

1. **bats-core** — a real shell test framework, MIT. Adds a tool to install
   locally and in CI for about ten assertions.
2. **Plain bash test script** — `scripts/test_loop.sh` sources `loop.sh`
   and checks results with a tiny `check` helper. No new tool; bash, `sh`
   and `sleep` exist on macOS and the ubuntu runner.
3. **No test** — breaks guideline 04 (every bug fix ships a regression test).

## Decision

Option 2. `loop.sh` returns early when sourced, `run_session` takes the
command to run, and the watchdog is its own function that exits on `TERM`.
`make scripts-test` runs the test and is part of `make check`.

## Consequences

- Each fixed bug has a test that fails without the fix.
- `make check` grows by about 2 s (one 1 s timeout case plus process
  start-up), locally and in CI.
- If the scripts grow enough that the hand-rolled helper hurts, moving to
  bats-core is a new ADR.
