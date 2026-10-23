---
id: ADR-0041
title: A refused connection ends connecting, other waits do not
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0041 — A refused connection ends connecting, other waits do not

## Context

ADR-0040 3(b) made `NWConnection.State.waiting` no exit from connecting, so
the local network prompt can be answered before the SDK backs off. M2.5's
tests showed what that costs when the desktop is simply not running.

Evidence, run on 2026-10-23 on macOS 26 (Swift 6.3.3): an `NWConnection`
with `NWProtocolWebSocket` to `ws://127.0.0.1:1/v1/ws` went `preparing` →
`waiting(POSIXErrorCode(rawValue: 61): Connection refused)` within 2 ms and
stayed there for the 3 s observed; it never reached `.failed`. The
`stopReleasesEverything(in: .backingOff)` test, with a 30 s connect bound,
never left connecting. `.waiting` retries only on a path change (Apple,
`NWConnection.State.waiting`: "waiting for a network path change").

ECONNREFUSED means a TCP reset came back: the host is reachable and nothing
listens on the port. Which error a pending or denied local network
permission gives an `NWConnection` is not verified here: the simulator has
no local network privacy (TN3179), so only M2.13's device step can show it.

## Options

1. **Keep every `.waiting` as no exit** — a desktop that is down costs the
   10 s connect bound on every attempt, so a returning desktop is reached up
   to 10 s later than with Kotlin, where a refusal ends connecting at once.
2. **`.waiting` with `.posix(.ECONNREFUSED)` exits to backing off; every
   other `.waiting` stays no exit** — same timing as Kotlin for a desktop
   that is down; the prompt case of ADR-0040 is untouched.
3. **Every `.waiting` exits** — ADR-0040 option 3(a), already rejected.

## Decision

Option 2. The refusal counts as a connect failure: it is logged once per
client like `.failed` (ADR-0036 E).

## Consequences

- SDD 06's connecting row names the refusal as an exit.
- If M2.13 shows the prompt surfacing as ECONNREFUSED, this decision is
  revisited there: the first attempt on a device would back off before the
  user answers, and the next one, 1 s later, would wait again.
- A host that answers with a reset for another reason (a firewall that
  rejects instead of dropping) also backs off at once, which is what a
  failure there should do.
