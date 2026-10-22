---
id: ADR-0015
title: Loop reports a timeout only when the watchdog's TERM killed the session
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0015 — Loop reports a timeout only when the watchdog's TERM killed the session

## Context

ADR-0014 keeps the polled watchdog and states, as a consequence, that a
session ending in the same poll as the deadline can still be reported as
timed out. The sixth M0 review (PLAN-0001 M0.R19) found two ways
`run_session` returns 124 for a session that was not killed:

- It removes a stale `<log>.done` but not a stale `<log>.timeout`, so any run
  on a log path that once timed out returns 124.
- `kill -TERM` succeeds on a session that has exited but is not yet reaped
  (`wait` has not run), so the watchdog touches `.timeout` for a session that
  ended by itself.

The marker alone cannot tell the two apart: whatever `kill` returns, the
session may have exited between the poll and the signal.

## Options

1. **Check that the pid is alive before `kill`** (`ps -o stat=`, not a
   zombie) — narrows the window but keeps it: the session can still exit
   between the check and the signal.
2. **Trust the session's exit status** — `wait` returns 143 (128 + `TERM`)
   only when the signal ended the process; a session that exited by itself
   keeps its own code, whatever the watchdog did afterwards.

## Decision

Option 2. `run_session` clears both markers before a session and returns
124 only when `.timeout` exists **and** the session's status is 143.

## Consequences

- A session that ends at the deadline returns its own code, so its report
  and verdict are used as usual; ADR-0014's last consequence no longer holds.
- A session that handles `TERM` and exits with another code after a real
  timeout is not reported as timed out; `loop.sh` then stops on the missing
  verdict line instead of the timeout message. `.timeout` stays beside the
  log as evidence.
- `make scripts-test` gains one session held 1.5 s past a 1 s timeout,
  about 2 s in total.
