---
id: ADR-0163
title: gRPC-Core leaves the iOS sample and end to end for a release gate job of its own
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-30
superseded_by:
---

# ADR-0163 — gRPC-Core leaves the iOS sample and end to end for a release gate job of its own

## Context

ADR-0109 linked gRPC-Core into `samples/ios` in Debug and Release, and made
`make e2e-ios` always run its call through the desktop's proxy. gRPC-Core is
a C++ build, and every job that builds the sample builds it cold
(DEBT-0080):

- On this Mac (Xcode 26.6), `make ios-sample` from an empty
  `build/ios-sample` took 429 s: three full builds of about 2.5 min each
  (Release excluding `AppMonitor.o`, Debug, Release), because the
  `EXCLUDED_SOURCE_FILE_NAMES` override rebuilds every package target too.
- On the release gate, `samples-ios` was cancelled at its 50 minute timeout
  (run 41092309585, 2026-10-30), `e2e-ios` at its 40 minute one (run
  41050714259) and took 36 min in run 40430481658.
- A cache of the derived data is no fix alone: the repository's Actions
  caches already hold 11.06 GB against GitHub's 10 GB limit, mostly Gradle
  entries of 1.2 GB written on each push to `dev`
  (`gh api repos/christianalexandre/app-monitor/actions/cache/usage`), and
  eviction is least recently used. A cache read only by gate runs, days
  apart, is the first one evicted.

The owner decided (2026-10-30, relayed to the loop by the owner's
interactive session): gRPC-Core leaves `make e2e-ios`, its call through the
proxy moves to a release gate job of its own, and a cache is kept only if
that job needs one to stay under its timeout.

## Options

1. **Two XcodeGen specs.** `samples/ios/project.yml` without gRPC-Core;
   `samples/ios/grpc.yml` includes it (XcodeGen appends the included spec's
   arrays and merges its dictionaries, checked with `xcodegen dump` 2.46.0)
   and adds the package, the bridging header and a `SAMPLE_GRPC` Debug
   condition that compiles the call and its button. `make ios-sample` and
   `make e2e-ios` build the first, `make e2e-ios-grpc` the second. One
   app, one source tree.
2. **A second target in one spec.** Every setting and the Run Script
   written twice, and `make ios-sample`'s checks must pick a target.
3. **Cache the derived data, keep one sample.** Evicted before the next
   gate run (above), and the cold path still passes the timeouts.

## Decision

Option 1. `e2e-ios` asserts the sample's URLSession, WebSocket, mock and
replay envelopes without the proxy; `e2e-ios-grpc` (`make e2e-ios-grpc`,
`scripts/e2e_ios.sh --grpc`, timeout 50 min) asserts the same plus the
gRPC-Core call through the proxy, as ADR-0109 and ADR-0124 had it in
`e2e-ios`. No cache: the job gets one only if a gate run shows it past its
timeout. The release no longer links gRPC-Core, so `samples-ios` builds no
C++ at all.

## Consequences

- `make e2e-ios` and `make ios-sample` no longer build C++: from empty
  derived data on this Mac, `make ios-sample` 36 s (was 429 s),
  `make e2e-ios` 28 s, `make e2e-ios-grpc` 149 s. `e2e-ios-grpc` is the
  one gate job that builds C++.
- One more macOS runner per gate run, and one more required check in
  `protect-main.json`, applied with the command in `AGENTS.md`.
- The sample's gRPC-Core button exists only in the `SampleGrpc` build;
  opened from `Sample.xcodeproj` it is absent.
- DEBT-0080 is paid by this split.
