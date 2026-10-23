---
id: ADR-0052
title: Release gate jobs, runners and minutes per run
status: Proposed # Proposed | Accepted | Superseded | Rejected
date: 2026-10-23
superseded_by:
---

# ADR-0052 — Release gate jobs, runners and minutes per run

## Context

PLAN-0004 M3.2 (a). The release gate (`release-gate.yml`, PRs to `main`)
must run the desktop on ubuntu, macOS and windows, the Kotlin ↔ Swift
contract tests, both end to ends, the packages and the SDK dry-run. The
repository is private (`gh api repos/christianalexandre/app-monitor` →
`"private": true`), so every minute is billed. Already decided and not
reopened: PR CI to `dev` is `make check` on ubuntu only (ADR-0012).

Evidence, read on 2026-10-23:

- [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions):
  2,000 included minutes a month on Free, 3,000 on Pro; beyond that, per
  minute, Linux 2-core $0.006, Windows 2-core $0.010, macOS $0.062 — macOS
  ≈10.3× and windows ≈1.7× a Linux minute. The page no longer states how
  windows and macOS minutes count against the included quota; ADR-0012
  assumed the older 10× / 2× multipliers, kept here as the worst case.
- Measured: the last 14 `ci.yml` runs (`make check`, ubuntu, warm `basic`
  cache) took 2.9–4.0 min each (`gh run list --workflow ci.yml`).
- Not measured yet (estimates, refined by M3.3–M3.10 from real runs):
  desktop tests alone ≈4 min on ubuntu or macOS and ≈6 on windows; the
  Swift contract job ≈15 min cold (`make swift-proto` builds `protoc`, ~1
  min, plus the simulator boot and `xcodebuild`); Android end to end ≈10 min
  on ubuntu with KVM; iOS end to end ≈10 min; each package ≈5 min; Maven
  dry-run ≈4 min; SwiftPM consumer ≈5 min.
- A release PR runs the gate once per push to it; the loop opens a release
  only when a plan closes, so a few runs a month.

## Options

1. **One job per check, as the plan lists them** — `branch`, `desktop`
   ×3 OS, `contract` (macOS), `e2e-android` (ubuntu), `e2e-ios` (macOS),
   `package` ×3 OS, `sdk-dry-run` (ubuntu + a macOS SwiftPM consumer). Clear
   red/green per check, parallel. macOS pays checkout, JDK, Gradle and
   simulator start-up four times: ≈4 + 15 + 10 + 5 + 5 ≈ 40 macOS min, ≈11
   windows, ≈25 ubuntu per run → ≈$2.7 a run beyond the quota, or ≈450
   quota minutes with the 10× / 2× multipliers (≈6 runs fill Pro's 3,000).
2. **One job per OS, the work grouped by runner** (recommended) — `branch`
   (ubuntu, seconds); `macos`: desktop tests, `:protocol:allTests`,
   `make swift`, `make e2e-ios`, `.dmg`, SwiftPM consumer, in one job that
   boots the simulator once; `windows`: desktop tests and `.msi`; `ubuntu`:
   desktop tests, `make e2e-android` (KVM), `.deb`, Maven dry-run. Steps
   keep their own names, so the failing one is still visible. ≈30 macOS,
   ≈9 windows, ≈20 ubuntu min → ≈$2.1 a run, ≈340 quota minutes worst case.
   A red macOS step hides the steps after it in that run.
3. **Option 2 with macOS jobs only on `workflow_dispatch`** — cheapest
   (ubuntu and windows on every push to the release PR, macOS by hand), but
   a release PR could be merged without the Swift and iOS checks, which
   the gate exists to prevent.
4. **A self-hosted runner on the owner's Mac** — macOS minutes free, real
   device possible (DEBT-0041); the gate then depends on that Mac being on
   and runs untrusted workflow code on it. Changes what the account pays
   for and what runs on the owner's machine.
5. **Make the repository public** — standard runners are free for public
   repositories (same billing page); a product and privacy decision, not
   a CI one.

## Decision

Empty while `Proposed`: cost is the owner's (`AGENTS.md`, "Licensing /
cost").

## Consequences

- Whichever option: every job has `timeout-minutes`, the gate runs only on
  PRs to `main` and `workflow_dispatch`, and M3.3–M3.10 report the measured
  minutes per job in their PRs so this estimate is replaced by data.
- Options 1–3 keep `ci.yml` on PRs to `dev` unchanged (ADR-0012).
