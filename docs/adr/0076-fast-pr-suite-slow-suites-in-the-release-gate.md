---
id: ADR-0076
title: PRs to dev run a fast suite; slow suites run only in the release gate
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0076 — PRs to dev run a fast suite; slow suites run only in the release gate

## Context

The owner, on 2026-10-24: the release gate runs only from `dev` to `main`,
so it does not hold the loop up; branches to `dev` still run tests, but a
faster suite.

Measured before this change:

- `make check` on the owner's Mac, warm Gradle cache: 2:42. It ran
  `swift-sim`, `ios-sample` and `kmp-sample` through `make swift` on every
  loop iteration (ADR-0047, ADR-0050, ADR-0073).
- PR CI `check` on ubuntu went from 5:48 to 8:46 when `samples/kmp`
  joined `./gradlew build` (PLAN-0005 M4.9 report).
- The loop dispatched the release gate on its own branches twice (M4.8a,
  M4.10) and waited for it: `contract` took 30.4 and 43.1 min, the gate
  ≈45 min, because `make swift` built `samples/kmp` (DEBT-0057).

Already decided, not reopened here: the gate's triggers (PRs to `main` and
`workflow_dispatch`, ADR-0052), the ruleset that requires it (ADR-0053),
`ci.yml` running `make check` on ubuntu (ADR-0012).

## Options

1. **Two suites.** `make check` drops simulator tests and sample apps: the
   Gradle modules but the samples, and `make swift` on the host only. The
   slow targets (`make swift-sim`, `make samples`, `make e2e-*`) run in the
   release gate; `make check-full` runs the non end to end ones locally.
   The loop never dispatches the gate; a task that changes a slow area runs
   that area's target locally.
2. **Keep one suite, cache Kotlin/Native across jobs** — the gate gets
   faster, the loop's per-iteration cost does not.
3. **Gate on every PR to `dev`** — the slowest option; what the owner ruled
   out.

## Decision

Option 1. The gate's `contract` job runs `make swift`, `make swift-sim`
and `:protocol:allTests`; a new `samples` job runs `make samples` in
parallel. The gate stays dispatchable for the owner's manual use.

## Consequences

- A sample, simulator or end to end regression is caught at the release PR,
  not at the PR that caused it; the fix is a `fix/` PR before `main`.
- A broken workflow file is caught at the release PR too.
- `/next` step 6 names the slow targets to run for the area a task changes.
- DEBT-0057 is paid: `kmp-sample` leaves `contract`.
