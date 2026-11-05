---
id: DEBT-0119
title: stopReleasesEverything missed the desktop's close once
status: Paid # Open | Paid | Dropped
date: 2026-11-03
trigger: "`MonitorClientTests.stopReleasesEverything` fails a second time, locally or on CI"
paid_date: 2026-11-05
---

# DEBT-0119 — stopReleasesEverything missed the desktop's close once

## What is missing

`stopReleasesEverything(.connected)` failed once in `make swift` on the macOS host: within its 5 s
`desktop.closes` never equalled `[1000]`. Either `MonitorClient.stop` can leave without its close,
or `TestDesktop` missed one or recorded another. The failure's log did not say which.

## Why it was accepted

One failure, in a run following the simulator suites. It did not repeat in the next five
`swift test` runs or `make check`, so there is nothing to investigate it with yet. The change it
appeared in touched no connection code.

## Trigger

`MonitorClientTests.stopReleasesEverything` fails a second time, locally or on CI: keep what
`desktop.closes` held, and reproduce it under load (the H5.2 method).

## How it was paid

It failed a second time on 2026-11-05, in 1 of 40 runs of the package's suite on the simulator.
Stopping a connected client 3,600 times with `TestDesktop` tracing every receive missed the close
19 times, and each trace was the same: the frame arrived with code 1000 in a callback that also
carried `ENOTCONN` (57). `TestDesktop.receive` returned on the error before reading the frame, so
`MonitorClient.stop` was right and the helper dropped its close. It now reads the close first, as
`MonitorClient.receive` does: 0 misses in 9,000 stops.

Left: why Network.framework reports both in one callback is not known.
