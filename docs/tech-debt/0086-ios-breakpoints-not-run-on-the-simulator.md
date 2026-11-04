---
id: DEBT-0086
title: iOS breakpoints are proven on the macOS host only
status: Paid
date: 2026-10-25
trigger: The first end to end that asserts a held call (`make server` resuming a `BreakpointHit`), or the next PR that changes `sdk/ios/TestHost/project.yml`
paid_date: 2026-11-04
---

# DEBT-0086 — iOS breakpoints are proven on the macOS host only

## What is missing

`BreakpointTests` runs in `swift test` on the macOS host. The simulator's
hosted bundle (`make swift-sim`) builds the SDK with `HoldProtocol` but
runs only the background and connection tests, and no end to end holds a
call: the headless server lets a held call go at its timeout (SDD 12).
Symptom: a CFNetwork difference on iOS, e.g. in when `stopLoading` runs,
would show only in a real app.

## Why it was accepted

The hold runs on the same CFNetwork classes as the mocks, which the iOS
end to end proves on the simulator (ADR-0117). Hosting the capture tests
there means moving `URLSessionCaptureTests` and its helpers into the
hosted bundle, or a server that answers a hit.

## Trigger

The first end to end that asserts a held call (`make server` resuming a
`BreakpointHit`), or the next PR that changes
`sdk/ios/TestHost/project.yml`.

## How it was paid

The headless server's `--breakpoints FILE` resumes each hit, and
`make e2e-ios` holds the sample's upload at both stages with a 10-minute
timeout, the response resumed `299`; the run asserts both hits, both
resumes and the `299` the device captured (ADR-0188). The request id of a
request-stage hit is the call's own, as on the host. Left host-tested
only: an abort, edits other than the status, a held task cancelled.
