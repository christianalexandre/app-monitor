---
id: DEBT-0050
title: Non-correctness findings of the second M3 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `debt_check.sh`
paid_date:
---

# DEBT-0050 — Non-correctness findings of the second M3 milestone review

## What is missing

Findings of the `code-review` of `168c0b9..de0d069` that are not wrong
results, crashes, hangs, leaks, races or privacy issues for anything run
today (ADR-0016):

- `.github/workflows/pr-conventions.yml`: a `release/*` → `main` PR checks
  `main..head`, which holds #95's squash `Co-authored-by:` trailer until a
  release PR merges (ADR-0060 states it). No `release/*` branch exists, and
  the check fails closed.
- `.github/workflows/pr-conventions.yml`: `generated with` is matched
  case-insensitively anywhere in a commit body, so prose such as "Swift
  files generated with protoc" fails `conventions`. Anchor it to the
  trailer's form.
- `scripts/debt_check.sh`: the "whole basename never matches by stem" rule
  reads every tracked file's basename, so an unrelated extension-less file
  named like a type silences that type's triggers. Path names now match as
  whole segments anywhere, so a short path (`src/main`) fires on every
  module. Both err on the side of noise or silence, never on a wrong exit.

Rejected: `e2e_ios.sh` passes the simulator's name, not its UDID, to
`ios_sample_check.sh`, but that script only builds for the simulator SDK;
the app is installed and launched by UDID. `sim_list.sh` requires the state
after the UDID and uppercase hex, which every `simctl list devices` line
has.

## Why it was accepted

None of them gives a wrong result for the branches, files and callers in
use today; they are noise, duplication or stale references.

## Trigger

The next PR that touches `debt_check.sh` pays its item.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.

Partly paid. `feat/kmp-sample-app` (touching the `Makefile`) paid the
`debt-check` item: the `Makefile`, `.claude/commands/next.md` and SDD 03's
scripts row cite ADR-0061 and say it exits 2 on an unresolved base; the
`Makefile` left the trigger with it (DEBT-0051).

`test/hermetic-end-to-end` touched `e2e_ios.sh` without paying its item: the
KMP end to end (PLAN-0005 M4.10) is a third caller of the simulator pick, so
the shared helper is written there, for all three at once. It paid the
DEBT-0049 item: that DEBT's index row no longer lists `debt_check.sh`, and its
paid items no longer sit under the template placeholder.

`test/kmp-end-to-end` (PLAN-0005 M4.10, ADR-0075) paid the `e2e_ios.sh` item:
`e2e_lib.sh`'s `boot_simulator` picks and boots the simulator for
`e2e_ios.sh` and `e2e_kmp.sh`, one parse of `sim_list.sh`'s output.
`ios_lib.sh`'s `ios_device` stays: it names a simulator for `xcodebuild`
without booting it, for the `make swift` scripts.

`ci/release-gate-cache` (PLAN-0015 H5.1) paid both `pr-conventions.yml`
items: the trailer check moved to `scripts/trailer_check.sh`, which matches
`generated with` only at a line start, and `test_trailer_check.sh` checks a
`release/x.y.z` branch cut from `dev` against `main` as M10 will open it.
`origin/main..origin/dev` carries no trailer (`trailer_check.sh`,
2026-10-30): #293 put #95 on `main`. The `debt_check.sh` item is left.
