---
id: ADR-0188
title: A held call in the iOS end to end
status: Accepted
date: 2026-11-04
superseded_by:
---

# ADR-0188 — A held call in the iOS end to end

## Context

DEBT-0086: the Swift SDK's breakpoints (ADR-0125) are tested by
`BreakpointTests` on the macOS host only. `make e2e-ios` runs the sample
on a simulator against the headless server, which sends mock rules
(`--mocks FILE`, ADR-0116) and asks a replay (`--replay PATH`, ADR-0122)
but sends no breakpoint, so no call is ever held on iOS itself. The
sample's calls are fixed (`samples/ios/Sample/SampleCalls.swift`); its
upload is a `PUT /put` data task with a body, which ADR-0125 holds.

Evidence, 2026-11-04, `make e2e-ios` on an iPhone 17 Pro simulator:
`BreakpointHit` at the request stage carries the `request_id` of the
`RequestStarted` sent after the resume (`build/e2e-ios/server.log`), and
the response stage's resumed status is what `ResponseReceived` carries
(SDD 07: the capture records what the app read).

## Options

### A. How the headless server gets breakpoints

1. **`--breakpoints FILE`**, JSON named as `BreakpointRule`'s fields, as
   `--mocks FILE` is, sent once to each session. A script writes it.
2. **Reuse `--mocks FILE`** with a breakpoint section. One flag fewer,
   but a file that is two messages, and `MockFile.kt` would hold both.

### B. What the server answers a hit

1. **Resume every hit**: the request unchanged, the response with an
   optional per-rule `resume_status_code`. The changed status proves the
   resume reached the device, and a rule `timeout_ms` longer than the end
   to end's wait proves the request's did: let go at its timeout, the call
   would not end in time.
2. **Abort, or edit more fields.** An abort's outcome (a failure) is the
   same as a timeout's would be for a mocked failure; more edits add
   flags for what `BreakpointTests` already covers on the host.
3. **Nothing, assert the hit only.** Shows the hold, not that the device
   reads a resume.

### C. Which end to ends

1. **`make e2e-ios` (and `e2e-ios-grpc`, the same script)**: the call
   held is the sample's upload, at both stages, rule timeout 10 minutes,
   the response resumed `299`; `e2e_lib.sh` asserts `held:request`,
   `held:response` and `status:299`.
2. **Also Android and KMP.** OkHttp and Ktor holds are JVM-tested against
   a real server already (SDD 12); not what DEBT-0086 is about.

## Decision

A.1, B.1 and C.1.

## Consequences

- `make e2e-ios` asserts a call held and resumed on the simulator; its
  upload ends after two round trips to the server, no measurable time.
- A resume the device never reads fails the end to end at `E2E_TIMEOUT`,
  not at once.
- Abort and edits other than the status stay host-tested
  (`BreakpointTests`).
