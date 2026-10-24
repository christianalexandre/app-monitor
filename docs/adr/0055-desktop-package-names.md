---
id: ADR-0055
title: Desktop package names, bundle id and unsigned dry-run packages
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0055 — Desktop package names, bundle id and unsigned dry-run packages

## Context

PLAN-0004 M3.2 (d), needed by M3.9: Compose Desktop `nativeDistributions`
for `desktop/app`, built by the gate as `.dmg`, `.msi` and `.deb`. Signed
packages are M10 (roadmap). Already decided: the window title is
`App Monitor` (`desktop/app/.../Main.kt`), the main class
`appmonitor.desktop.app.MainKt`.

Evidence, read on 2026-10-23:

- `desktop/app/build.gradle.kts` sets only `mainClass`; no
  `nativeDistributions` block, no `packageName`, no bundle id.
- Compose Desktop's `nativeDistributions` takes `packageName`,
  `packageVersion` (`.msi` needs `MAJOR.MINOR.BUILD`, MAJOR and MINOR at
  most 255; `.deb` must start with a digit),
  `macOS { bundleID }`, `windows { upgradeUuid }`, `linux { packageName }`
  ([Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)).
- An unsigned, unnotarized `.dmg` opens on macOS only after the user
  overrides Gatekeeper; an unsigned `.msi` triggers SmartScreen.
- The iOS sample uses `dev.appmonitor.*`; `appmonitor.dev` is a registered
  domain the owner may not hold (ADR-0054).

## Options

1. **`App Monitor` / `dev.appmonitor.desktop` / `app-monitor`** —
   `packageName = "App Monitor"` (`.dmg`, `.msi`, Start menu),
   `bundleID = "dev.appmonitor.desktop"`, Linux package `app-monitor`,
   `packageVersion = "1.0.0"` until the first real release; consistent
   with the iOS sample, but a reverse-DNS id on a domain not proven owned.
2. **Same names with `io.github.christianalexandre.appmonitor`** as the
   bundle id (recommended if ADR-0054 picks that group) — provably the
   owner's; long, and changing a bundle id after release loses user
   preferences and permissions on macOS.
3. **Packaging deferred to M10** — no names now; M3.9 would drop and the
   plan's goal loses "the desktop packages build".

In Options 1 and 2 the gate's packages are unsigned dry-runs, uploaded with
`retention-days: 7`, not attached to a release; `upgradeUuid` is generated
once and kept in the build file, since changing it breaks Windows upgrades.

## Decision

Option 2, decided by the owner on 2026-10-23: `packageName = "App
Monitor"`, Linux package `app-monitor`, `bundleID =
"io.github.christianalexandre.appmonitor.desktop"`.

The packages stay **unsigned**, at M3 and at M10: signing and notarizing
cost money (Apple Developer Program), which the project does not spend.
Distribution uses free channels only — GitHub Releases and a Homebrew tap
of the owner's — and the README tells the user how to let an unsigned app
run on macOS and Windows.

## Consequences

- A bundle id chosen now is kept; changing it later is a new app for
  macOS, which loses its preferences and permissions.
- The official `homebrew/cask` tap announced, in Homebrew 5.0 (November
  2025), the removal of casks that fail Gatekeeper by 2026-09-01, and
  deprecated `--no-quarantine`; an unsigned app therefore ships in a tap of
  the owner's, and the user clears the quarantine attribute by hand. M10
  checks what Homebrew enforces then.
- Unsigned dry-runs are build proof only; SDD 02 must say so.
