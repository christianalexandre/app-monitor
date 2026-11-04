---
id: DEBT-0114
title: The iOS MonitorClientTests time out in most local `make swift` runs
status: Paid
date: 2026-10-31
trigger: A `make swift` run fails on an `eventually` in `MonitorClientTests.swift`, or a PR changes `TestDesktop.swift`'s timing
paid_date: 2026-11-04
---

# DEBT-0114 — The iOS MonitorClientTests time out in most local `make swift` runs

## What is missing

On `origin/dev` at #338, `make swift` failed in 4 of 5 runs on the owner's
Mac (2026-10-31, FortiClient's `utun` interfaces up, load about 2.5), each
time in `MonitorClientTests.swift` on an `eventually` that ran out its 5 s:
`connectTimeoutBacksOffAndLogsOnce` (three runs), `noPongBacksOff`,
`aConnectFailureAfterAGoodSessionIsLoggedAgain`,
`dropsAreReportedBeforeTheFirstEvent` and
`aReconnectReportsWhatThePreviousSessionWrote`. The same tests passed
alone, `connectTimeoutBacksOffAndLogsOnce` 5 of 5. CI does not see it:
the `check` job runs on Linux, where `make swift` is skipped.

## Why it was accepted

Found in the H5.4 iteration, whose change is an ADR only and touches no
Swift. The cause is not known: a time bound too tight for the suite run
in parallel, or the VPN (as in ADR-0164) are both open.

## Trigger

A `make swift` run fails on an `eventually` in `MonitorClientTests.swift`, or a PR changes `TestDesktop.swift`'s timing.

Re-triggered by PLAN-0015 H5.4 (2026-10-31), which changed both files: four runs on the owner's Mac were green (`swift test` once, `make swift` inside `make check`, `make swift` twice more), so nothing to reproduce on the older commits; the next failure is the case to study.

Before: The next PR that runs `make swift`, or one that changes
`MonitorClientTests.swift` or `TestDesktop.swift`. That PR reproduces it on
the commit before #336 and on `origin/dev` to find the cause.

## How it was paid

Reproduced by PLAN-0016 H6.R1 with FortiClient `Disconnected`: `swift test` failed 5 of 8
runs, and `--filter MonitorClientTests` 6 of 6, so neither the VPN session nor the parallel run
is the cause. Logging each `NWConnection`'s state showed every stalled attempt in
`waiting(EADDRINUSE)`. `TestDesktop` listened on port 0, in the range clients' source ports come
from, and the ports here go in sequence: a client on 59909 with its desktop on 59910, then every
further connect in use. Closing the desktop's side on the peer's end and dropping
`allowLocalEndpointReuse` both left it failing, 6 of 6 each. With the listener on a random port
outside that range, `--filter MonitorClientTests` passed 8 of 8 and `swift test` 8 of 8, 5.7 s each.

Left: the source port of a failing attempt was not observed, the mechanism is read from the
neighbouring ports and the fix's result. FortiClient's network extension stayed active
(`systemextensionsctl`), so whether it makes the ports sequential is not known. A real desktop on
47474 is outside that range; a headless server on a free port, as in the end to ends, is not, and
none has failed this way.
