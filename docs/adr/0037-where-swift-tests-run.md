---
id: ADR-0037
title: Where the Swift tests run
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0037 — Where the Swift tests run

## Context

From M2.3 on, `sdk/ios` has `swift test` and, for background sessions
(M2.9) and the sample (M2.12), `xcodebuild` on the iPhone simulator. Both
need macOS. PR CI runs `make check` on ubuntu only, and macOS minutes are
reserved for the release gate (ADR-0012, roadmap M3). A macOS job is a cost
decision, a "stop and ask" area (`AGENTS.md`).

Already decided, not reopened here:

- PR CI: `make check` on `ubuntu-latest`, on PRs to `dev` and pushes to
  `dev` (ADR-0012). Kotlin iOS targets are already compiled but not run in
  CI; a PR that only breaks iOS is caught by the local `make check`.
- The release gate (M3) runs the Kotlin ↔ Swift contract tests and the iOS
  e2e on macOS, on PRs to `main` only.
- The loop and the owner both run `make check` on macOS.

Evidence, checked on 2026-10-23:

- GitHub-hosted runner rates: Linux 2-core $0.006/min, macOS 3- or 4-core
  $0.062/min (10.3×), rounded up per job to the whole minute
  (<https://docs.github.com/en/billing/reference/actions-minute-multipliers>).
- The repository is private; minutes count against the account (ADR-0012).
- Not measured yet: a cold `swift test` of `sdk/ios` with swift-protobuf.
  M2.3 measures it and records the number in its PR.

## Options

1. **Local only until M3.** `make check` runs the Swift gates (`make swift`,
   M2.3) when `swift` is on the PATH and prints one skip line otherwise, so
   ubuntu CI is unchanged and every loop iteration and owner run on macOS
   executes them. M3's release gate runs them on macOS before `main`. A PR
   that breaks Swift only passes CI — the same exposure the Kotlin iOS
   targets already have. Cost: none.
2. **A macOS job on every PR, path-filtered** to `sdk/ios/**`,
   `protocol/**`, `samples/ios/**`. Catches a Swift break before merge even
   when the PR was not run on a Mac. Cost: ~10× an ubuntu minute for every
   such PR push; most M2 PRs touch those paths.
3. **A macOS job on push to `dev` only** — one run per merge, so a break is
   found right after it lands, not before. Cost: one macOS run per M2 merge.

Recommended: 1. Every PR of this project is produced on macOS, by the loop
or the owner, with `make check` as a required step; option 2 pays 10× for
a case that does not occur here. If a contributor without a Mac appears,
option 2 is the trigger to revisit.

## Decision

The owner accepted option 1: the Swift gates run in local `make check` when
`swift` is on the PATH, and in M3's release gate on macOS; CI stays on
ubuntu.

## Consequences

- Option 1: "what was actually run" in each M2 PR must list the Swift gate;
  a skip line in the `make check` output means it did not run.
- Option 2 or 3: a new job in `.github/workflows/ci.yml`, and ADR-0012's
  "iOS simulator tests are not run in CI until M3" no longer holds.
