---
id: ADR-0227
title: The desktop's packages are built per OS on each release, named for the tap and the bucket
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0227 — The desktop's packages are built per OS on each release, named for the tap and the bucket

## Context

PLAN-0021 M11.2. The owner decided on 2026-11-25 to go straight to `1.0.0`, libraries and
desktop at one tag, with unsigned packages (ADR-0055).

Known constraints:

- `jpackage` builds only for the OS it runs on, and only from a JDK of that OS (Compose's
  [native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)).
- Its `app-version` is numbers only. On macOS the first number cannot be 0: JDK 21's
  `jpackage --app-version 0.1.0` says *"The first number in an app-version cannot be zero or
  negative"*, run 2026-11-25. `packageVersion` was a fixed `1.0.0`.
- Compose names its packages `App Monitor-1.0.0.dmg`, `app-monitor_1.0.0-1_amd64.deb` and so
  on, read from a local build. A space in a release asset's name is awkward in the cask's and
  the manifest's URLs.
- `macos-latest` on GitHub Actions is Apple silicon. The local `.app` is ad-hoc signed by
  jpackage (`codesign -dv`: `Signature=adhoc`), and passes `codesign --verify --deep
  --strict`.

## Options

1. **One job per OS in the publish workflow, packages renamed by one script.** A `desktop`
   matrix (macOS, Windows, Linux) runs `scripts/desktop_packages.sh build`. The script runs
   the OS's Gradle tasks and copies the packages as `app-monitor-X.Y.Z-<os>-<arch>.<ext>`.
   The `release` job attaches them. On a pull request that changes the packaging, the same
   jobs run dry and keep the packages as artifacts.
2. **Build on the owner's machines and upload by hand.** No CI time, but every release waits
   on three machines, and nothing checks the packaging between releases.
3. **Option 1 plus an Intel macOS package** (`macos-13`, x64). More CI time for a machine
   kind Homebrew itself is leaving behind
   ([Homebrew 5.0.0](https://brew.sh/2025/11/12/homebrew-5.0.0)). A developer on Intel still
   runs the desktop from the repository.

## Decision

Option 1:

- `packageVersion` is `VERSION_NAME` without its suffix, and `dev` moves to
  `1.0.0-SNAPSHOT`.
- `.rpm` joins `.dmg`, `.msi` and `.deb`.
- Windows also gets a `.zip` of the app image, for Scoop, which installs from an archive.
- macOS is Apple silicon only.

## Consequences

- A tag publishes the libraries and the desktop's packages together. A release fails if any
  OS fails to package.
- A pull request touching `desktop/app/build.gradle.kts` or the script costs three runners for
  the dry run.
- No `0.x` version can be packaged on macOS again. The version check does not need to say
  so: the macOS job fails with jpackage's message.
- Intel Macs and Linux on ARM have no package.
