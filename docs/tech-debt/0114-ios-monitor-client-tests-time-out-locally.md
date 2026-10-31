---
id: DEBT-0114
title: The iOS MonitorClientTests time out in most local `make swift` runs
status: Open
date: 2026-10-31
trigger: The next PR that runs `make swift`, or one that changes `MonitorClientTests.swift` or `TestDesktop.swift`
paid_date:
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

The next PR that runs `make swift`, or one that changes
`MonitorClientTests.swift` or `TestDesktop.swift`. That PR reproduces it on
the commit before #336 and on `origin/dev` to find the cause.

## How it was paid

> Filled when closing.
