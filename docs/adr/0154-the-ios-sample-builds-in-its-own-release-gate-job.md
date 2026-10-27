---
id: ADR-0154
title: The iOS sample builds in its own release gate job
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-26
superseded_by:
---

# ADR-0154 — The iOS sample builds in its own release gate job

## Context

The release gate's `samples` job ran `make samples`: the Android sample and
the Gradle plugin's TestKit tests, the iOS sample in Debug and Release, and
the KMP sample on Android and iOS, one after the other on one macOS runner,
with `timeout-minutes: 50`. The iOS sample builds `grpc/grpc-ios` from
source since ADR-0109 (DEBT-0080: 254 s Debug, 128 s Release cold on the
owner's Mac, more on a hosted runner).

Evidence: #293's release gate (run 40412489599) cancelled `samples` at
50 min in "Sample apps, debug and release"; every other job passed,
`e2e-ios` in 28 min. The last green gate (2026-10-24) predates the gRPC
sample (PLAN-0009 M8.8).

`samples` is a required check of `main` by name
(`.github/rulesets/protect-main.json`).

## Options

1. **A `samples-ios` job beside `samples`**: `samples` keeps its name and
   runs `make android-samples kmp-sample`, `samples-ios` runs `make
   ios-sample`; both required. Halves the longest path, no cache to trust;
   the ruleset gains one name and must be applied again.
2. **Raise `samples`' timeout.** One line, but the gate's longest job grows
   past an hour and keeps growing with each sample.
3. **Cache the derived data of `build/ios-sample`** keyed on
   `Package.resolved` (DEBT-0080's own idea). Fastest when warm, but the
   gate must trust a cache's invalidation, and a cold run still times out.

## Decision

Option 1. `samples-ios` needs no Java or Gradle; it uploads
`build/ios-sample.log` on failure. `protect-main.json` lists `samples-ios`
after `samples`, applied with the command in `AGENTS.md`.

## Consequences

- A release gate takes one more macOS runner at a time.
- DEBT-0080 stays open for the cache, its trigger moved to `samples-ios`.
- Until the ruleset is applied, `samples-ios` runs but does not block a
  merge to `main`.
