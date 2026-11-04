---
id: ADR-0196
title: The loop's watchdog ends the session's whole process tree, and its kill is the timeout
status: Accepted
date: 2026-11-04
superseded_by:
---

# ADR-0196 — The loop's watchdog ends the session's whole process tree, and its kill is the timeout

## Context

ADR-0014's polled watchdog stays. It sends `TERM` to the `claude` pid only
(DEBT-0013), and ADR-0015 reports a timeout only on exit 143 (DEBT-0016). PLAN-0016
H6.6 asks for the process group to be killed and any exit after a kill to count as a
timeout. Evidence, from a real `make loop` session on macOS (`ps -o pid,ppid,pgid`):

- `claude` 12228 is in group 83564; the shell of its Bash tool is its child, 13584,
  in **its own group 13584**. A group kill reaches `claude`, and no command it runs.
- Starting a group of our own needs job control. ADR-0014 measured `set -m` without a
  tty killing the calling shell. It would also leave the session out of the terminal's
  foreground group, so Ctrl-C would stop reaching it, and macOS has no `setsid`.
- The old `run_session`, given the new test's session that ignores `TERM`, never
  returned: its `wait` has no bound.

## Options

1. **Process group** — what the plan says. Misses every tool command (above), and
   needs job control.
2. **Walk the tree with `pgrep -P`** — one call per process, no change to how the session
   starts. Finds any process still reachable through its parents, whatever its group;
   misses one whose parent exited before the walk, such as a daemon already detached.
3. **Trust the exit status** (ADR-0015) or **the marker** for the timeout: the status
   misses a session handling `TERM`. The marker alone misreports a session that exits
   in the moment between the last poll and the signal, which ADR-0015 ruled out.

## Decision

Options 2 and the marker. At the deadline `watchdog` lists `descendants` of the session,
sends `TERM` to all, waits up to `KILL_GRACE` (10 s) while any lives, walks again and
sends `KILL` to what is left. `run_session` returns 124 whenever `.timeout` exists,
whatever the code. Supersedes ADR-0015.

## Consequences

- No test JVM, `make desktop` or simulator tool of a timed out session keeps a port for
  the next iteration, and a session that ignores `TERM` no longer blocks the loop. A
  Gradle daemon already detached survives, as DEBT-0013 expected; one still a child of
  its client dies, and the next build starts another.
- A session that finishes by itself in that moment at the deadline is reported as timed
  out and the loop stops: one poll (0.2 s) in `ITERATION_TIMEOUT`, its report still printed.
- A timeout takes up to `KILL_GRACE` longer. A PID reused inside the grace would get the
  `KILL`; not guarded, the cost being one process in the moment a loop already stops.
- `make scripts-test`: `test_loop.sh` went from 10 s to 18 s (measured here, both on this
  branch's machine), its new sessions waiting out a 1 s deadline and grace.
