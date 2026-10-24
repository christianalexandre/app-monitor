---
id: DEBT-0050
title: Non-correctness findings of the second M3 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `pr-conventions.yml`, `debt_check.sh`, `e2e_ios.sh` or `0049-m3-review-round-1-cleanups.md`, or the first `release/*` branch opened to `main`
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
- `scripts/e2e_ios.sh`: repeats the tool check and the simulator pick of
  `scripts/ios_lib.sh`, with a second parse of `sim_list.sh`'s output
  (`substr($0, 38)` against `cut -d' ' -f2-`).
- DEBT-0049: the paid `debt_check.sh` item was removed from *What is
  missing* while *How it was paid* still names it under the template
  placeholder, and its index row still lists `debt_check.sh`.

Rejected: `e2e_ios.sh` passes the simulator's name, not its UDID, to
`ios_sample_check.sh`, but that script only builds for the simulator SDK;
the app is installed and launched by UDID. `sim_list.sh` requires the state
after the UDID and uppercase hex, which every `simctl list devices` line
has.

## Why it was accepted

None of them gives a wrong result for the branches, files and callers in
use today; they are noise, duplication or stale references.

## Trigger

The next PR that touches `pr-conventions.yml`, `debt_check.sh`,
`e2e_ios.sh` or `0049-m3-review-round-1-cleanups.md` pays that
file's item. The first `release/*` branch opened to `main` pays the
squash-trailer item, or finds #132 merged.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.

Partly paid. `feat/kmp-sample-app` (touching the `Makefile`) paid the
`debt-check` item: the `Makefile`, `.claude/commands/next.md` and SDD 03's
scripts row cite ADR-0061 and say it exits 2 on an unresolved base; the
`Makefile` left the trigger with it (DEBT-0051).
