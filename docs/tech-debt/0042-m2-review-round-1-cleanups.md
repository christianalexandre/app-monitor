---
id: DEBT-0042
title: Non-correctness findings of the first M2 milestone review
status: Open
date: 2026-10-23
trigger: The next PR that touches one of the files listed below
paid_date:
---

# DEBT-0042 — Non-correctness findings of the first M2 milestone review

## What is missing

Findings of the `code-review` of `2d0f0ca..9dee53b` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- `sdk/ios/Sources/AppMonitor/MonitorClient.swift`: `Endpoint.url` brackets
  any host with `:` and does not percent-encode an IPv6 zone id, so a manual
  `host: "fe80::1%en0"` builds no URL; the client logs "not a valid host"
  and backs off.
- `sdk/ios/Sources/AppMonitor/URLSessionCapture.swift`: `started` reads
  `URLSessionCapture.sink` twice (options, then sink); one read into a local
  is clearer. Only tests swap the sink. The `http`/`https` guard relies on
  Foundation rewriting a WebSocket task's `ws`/`wss` URL before `resume`,
  seen on the macOS host and the iOS 26.5 simulator (M2.13), not on older
  iOS; accepting `ws`/`wss` too costs nothing.
- `sdk/ios/Sources/AppMonitor/WebSocketCapture.swift`: `WebSocketHooks.started`
  builds `RequestStarted` by hand instead of reusing `requestStarted`, and
  `TaskCapture.didDownload` copies the file-head read of `requestStarted`.
- `samples/ios/Sample/SampleCalls.swift`: `BackgroundDownload.bytes` is not
  reset between runs, so a run whose download callback never fires reports
  the previous run's size.
- `scripts/ios_sample_check.sh`: the simulator selection and the Info.plist
  key loop are copied from `scripts/swift_sim_check.sh`.

## Why it was accepted

None of them changes what reaches the desktop for a supported input today;
each is a contained change best made beside the next edit of its file.

## Trigger

The next PR that touches `MonitorClient.swift`, `URLSessionCapture.swift`,
`WebSocketCapture.swift`, `SampleCalls.swift`, `ios_sample_check.sh` or
`swift_sim_check.sh` pays that file's items.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
