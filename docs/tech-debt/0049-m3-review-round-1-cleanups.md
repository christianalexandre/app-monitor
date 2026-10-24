---
id: DEBT-0049
title: Non-correctness findings of the first M3 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `debt_check.sh`, `e2e_android.sh`, `e2e_lib.sh`, `publish_dry_run.sh`, `release-gate.yml` or `desktop/app/build.gradle.kts`, or M10's first published desktop package
paid_date:
---

# DEBT-0049 — Non-correctness findings of the first M3 milestone review

## What is missing

Findings of the `code-review` of `40b9891..168c0b9` that are not wrong
results, crashes, hangs, leaks, races or privacy issues for anything run
today (ADR-0016):

- `scripts/e2e_android.sh`: the local network grant runs only when
  `ro.build.version.sdk` is 37 or more. A preview image of Android 17 still
  reports 36 (the 37.2 image did, M3.5), so the grant is skipped there. The
  gate and the Mac run API 36 images, where no grant is needed.
- `scripts/debt_check.sh`: a trigger path matches a file only as the whole
  path, a prefix or a suffix, so `ios/Sources` never matches
  `sdk/ios/Sources/x.swift`. The stem rule matches too much: `Makefile`
  matches `Makefile.bak`.
- `desktop/app/build.gradle.kts`: `packageVersion` is fixed at `1.0.0`, with
  a fixed Windows `upgradeUuid`. A second MSI with the same version does not
  upgrade the first one. Only dry-run packages exist (ADR-0055).
- `scripts/publish_dry_run.sh`: the POM check covers the `''`, `-jvm` and
  `-android` publications only. `appmonitor-protocol`'s `iosArm64` and
  `iosSimulatorArm64` POMs are not checked.
- `release-gate.yml`: `e2e-android` builds the sample and the server, then
  `make e2e-android` builds them again. `contract` runs `make swift`, which
  builds the iOS sample that `e2e-ios` builds again on another macOS runner.
- `scripts/e2e_lib.sh`: `missing` sets `method`, `url`, `terminal`, `frames`
  and `id` without `local`. Every caller runs it in `$(...)` today.

## Why it was accepted

None of them gives a wrong result on the images, packages and callers in use
today. The duplicate builds cost minutes, not correctness.

## Trigger

The next PR that touches `debt_check.sh`, `e2e_android.sh`, `e2e_lib.sh`,
`publish_dry_run.sh` or `release-gate.yml` pays that file's item. The
`packageVersion` item is paid by the next PR that touches
`desktop/app/build.gradle.kts`, or before M10's first published desktop
package.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
