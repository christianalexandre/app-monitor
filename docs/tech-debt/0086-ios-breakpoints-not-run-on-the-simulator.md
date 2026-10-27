---
id: DEBT-0086
title: iOS breakpoints are proven on the macOS host only
status: Open
date: 2026-10-25
trigger: The first end to end that asserts a held call (`make server` resuming a `BreakpointHit`), or the next PR that changes `sdk/ios/TestHost/project.yml`
paid_date:
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

> Filled when closing. What the solution taught, and what is left of it.
