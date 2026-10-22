---
id: ADR-0014
title: Loop watchdog polls a done marker instead of trapping TERM
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0014 — Loop watchdog polls a done marker instead of trapping TERM

## Context

ADR-0013 made the `loop.sh` watchdog its own function that `run_session`
cancels with `TERM`; the bash test and `make check` stay as decided there.
The fourth M0 review (PLAN-0001 M0.R13) found that a `TERM` landing before
`sleeper=$!` orphans the watchdog's `sleep`. Measured on macOS bash 3.2.57
(`/bin/bash`, what `#!/usr/bin/env bash` finds here), 200 to 500 watchdogs
sent `TERM` right after they start:

- `origin/dev`: 1 to 7 orphaned `sleep`s per 200.
- A `cancelled` flag checked after `sleeper=$!`: about 30 per 500. Its
  `kill` reaches the forked child before it execs `sleep`; that child still
  has bash's handler, and the `exec` drops the pending signal.
- The flag plus `kill -KILL` on the sleeper: none leaked, but one watchdog
  lost the `TERM` itself (it landed before `trap` ran) and hung its waiter
  for the whole timeout. In `make loop`, that is a session that exits at
  once, such as `claude` failing at startup, blocking for `ITERATION_TIMEOUT`.
- Its own process group (`set -m`, `kill -KILL -- -pgid`): about 19 per 500,
  and job control without a tty killed the calling shell (exit 144).

## Options

1. **Trap `TERM`, harden the trap** — the variants above. Every one leaves a
   window where bash drops or misroutes the signal.
2. **External timeout tool** (`timeout`, `perl -e`) — GNU `timeout` is not
   on stock macOS; a perl one-liner adds a language to two bash scripts.
3. **Poll a marker** — `run_session` touches `<log>.done` when the session
   ends; the watchdog checks it every `poll_s` (0.2 s) until the deadline. No
   signal is used to cancel it, and its only child is a foreground `sleep`
   that has always ended by the time the watchdog exits.

## Decision

Option 3. `watchdog <pid> <timeout-marker> <done-marker>` returns when the
done marker appears and otherwise kills `<pid>` at `timeout_s`;
`run_session` removes the marker before and after each session.

## Consequences

- Cancellation is deterministic, so the test is too: 20 instant sessions
  leave no `sleep`, no `.timeout`, no `.done`.
- Each `run_session` waits up to one poll (0.2 s) after its session ends.
  Nothing in an hour-long iteration, but `make scripts-test` goes from about
  2 s to about 4 s, mostly the 20 instant sessions at a 0.11 s poll.
- A session that ends in the same poll as the deadline can still be reported
  as timed out, as before.
