---
id: DEBT-0049
title: Non-correctness findings of the first M3 milestone review
status: Open
date: 2026-10-24
trigger: The first Android 17 preview image in use, or M10's first published desktop package
paid_date:
---

# DEBT-0049 — Non-correctness findings of the first M3 milestone review

## What is missing

Findings of the `code-review` of `40b9891..168c0b9` that are not wrong
results, crashes, hangs, leaks, races or privacy issues for anything run
today (ADR-0016):

- `scripts/e2e_lib.sh`'s `grant_local_network` (moved from
  `scripts/e2e_android.sh`): the local network grant runs only when
  `ro.build.version.sdk` is 37 or more. A preview image of Android 17 still
  reports 36 (the 37.2 image did, M3.5), so the grant is skipped there. The
  gate and the Mac run API 36 images, where no grant is needed.
- `desktop/app/build.gradle.kts`: `packageVersion` is fixed at `1.0.0`, with
  a fixed Windows `upgradeUuid`. A second MSI with the same version does not
  upgrade the first one. Only dry-run packages exist (ADR-0055).
- `scripts/publish_dry_run.sh`: the POM check covers the `''`, `-jvm` and
  `-android` publications only. `appmonitor-protocol`'s `iosArm64` and
  `iosSimulatorArm64` POMs are not checked.

## Why it was accepted

None of them gives a wrong result on the images, packages and callers in use
today. The duplicate builds cost minutes, not correctness.

## Trigger

The first Android 17 preview image the gate or the Mac runs pays the
local network grant item. The `packageVersion` item is paid before M10's
first published desktop package. It fired on `feat/desktop-app-on-storage`
(M5.4), which only adds a dependency and the `java.sql` module there: no
package is published yet, and the version scheme belongs to the release.

## How it was paid

Partly paid: the `scripts/debt_check.sh` item, in `fix/debt-check-unknown-base`
(ADR-0061): paths match by whole segments anywhere, and a name that is a
file's whole basename never matches by stem.

The `scripts/publish_dry_run.sh` item, in `build/sdk-ktor-no-op-publication`
(ADR-0072): every artifact's `-iosarm64` and `-iossimulatorarm64` POMs are
checked, `appmonitor-protocol`'s included.

`test/hermetic-end-to-end` (ADR-0074) touched `e2e_lib.sh`, `e2e_android.sh`
and `release-gate.yml`: `missing` declares its variables `local`. The
`e2e_android.sh` grant stays on the SDK level: the API 37 emulator image's
`pm list permissions` does not list `ACCESS_LOCAL_NETWORK`, so there is no
presence check to use; it moves to the first Android 17 preview image the gate
or the Mac runs. The `release-gate.yml` double builds move to M4.10, which
adds the KMP end to end beside the two jobs and reshapes their builds.

`test/kmp-end-to-end` (PLAN-0005 M4.10, ADR-0075) paid the `release-gate.yml`
double builds: `e2e-ios` builds the Debug app only, Release being `contract`'s
(`make swift`), and `e2e-kmp-ios` does the same. Each end to end job still
builds its Debug app beside `contract`, since waiting for `contract`'s would
lengthen the gate. `e2e-android` and `e2e-kmp-android` build before the
emulator boots; the script's own `./gradlew` call is then up to date. The
grant moved into `e2e_lib.sh`, still on the SDK level, for both Android end to
ends.
