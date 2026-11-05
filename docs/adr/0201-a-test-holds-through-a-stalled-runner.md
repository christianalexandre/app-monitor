---
id: ADR-0201
title: A test holds through a stalled runner, and iOS suites are repeated before a release
status: Accepted
date: 2026-11-05
superseded_by:
---

# ADR-0201 — A test holds through a stalled runner, and iOS suites are repeated before a release

## Context

Release PR #379's `contract` job failed on other tests each run. PR #382 found
six causes, none in the SDK, and all of the same kinds: a 0.3 s pong bound in
tests not about it, a click or a next session that did not wait, a timer
measured with `Date`, a port and an endpoint given one try.

An idle Mac hid them. The package's suite passed 10 of 10 there, then failed 2 of
6 under 60 busy loops, as on CI. Fixing what the gate showed was slow, each round
45 minutes, and each fix uncovered the next failure.

The same method on the Kotlin side, on 2026-11-05: 832 JVM tests passed 4 rounds
of 4 under 60 busy loops, and under 200 only `LifecycleTest.callInFlightCompletesDuringStop`
failed (3 of 3, DEBT-0128). They need no such step.

## Options

1. **Retry failed tests in CI** (`-retry-tests-on-failure`) — green and quick, but
   guideline 04 calls that a retry loop, and it hides real races such as these.
2. **Rules in guideline 04, and `make swift-stress` by hand** — keeps the gate as
   it is, and finds these before a push, at about 35 minutes of the owner's Mac.
3. **A stress job in the gate** — always runs, but a hosted runner repeating the
   suites costs a gate more minutes, and its failures arrive too late again.

## Decision

Option 2. Guideline 04 takes the rules. `make swift-stress` runs before opening a
release, and for any iOS test that failed once.

## Consequences

- Nothing enforces running it: it is one more item for whoever opens a release.
- Load on a 10-core Mac imitates a hosted runner and is not one: DEBT-0128 lists
  what failed on CI and was never reproduced here.
- Windows has no such command: its one failure did not reproduce on macOS.
