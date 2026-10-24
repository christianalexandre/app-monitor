---
id: ADR-0058
title: SDK publication dry-run with maven-publish, a protocol artifact and a path SwiftPM consumer
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0058 — SDK publication dry-run with maven-publish, a protocol artifact and a path SwiftPM consumer

## Context

PLAN-0004 M3.10: publish the Kotlin SDKs with ADR-0054's coordinates to a
local repository with complete POMs, resolve the iOS package from a
throwaway SwiftPM project in debug and release, and measure DEBT-0040.
Maven Central, signing and tags are M10 (`docs/plans/README.md`). Already
decided: group `io.github.christianalexandre`, artifacts `appmonitor-core`,
`appmonitor-okhttp`, `appmonitor-no-op`, a root `Package.swift`, one
`X.Y.Z` version (ADR-0054).

Evidence, measured on 2026-10-23 on this machine (Gradle 9.7.1, Kotlin
2.4.21, AGP 9.3.3, Swift 6.3.3):

- `sdk/core` has `implementation(project(":protocol"))`; Gradle writes a
  project dependency into the POM with that project's coordinates, so
  `protocol` must be published too or `appmonitor-core` cannot resolve.
- With Gradle's `maven-publish` applied, the Kotlin and Android KMP plugins
  create one publication per target plus the root one (`kotlinMultiplatform`,
  `jvm`, `android`; `protocol` adds `iosArm64`, `iosSimulatorArm64`), each
  with a sources jar. Renaming the `artifactId` prefix of each one in
  `configureEach` propagates to the root `.module` (`available-at`) and to
  the dependent POMs: `appmonitor-okhttp-android`'s POM depends on
  `appmonitor-core-android` and `appmonitor-protocol-android`.
- `scripts/publish_dry_run.sh`: Gradle publish ≈12 s cold, the consumer's
  debug and release builds ≈25 s cold; 35 s in total.
- The consumer (a macOS executable calling `AppMonitor.start` and `stop`)
  links in release 3,636,088 bytes with 14,767 `SwiftProtobuf` symbols, in
  debug 5,967,920 bytes with 18,095.

## Options

Publishing plugin:

1. **Gradle's `maven-publish`** (chosen) — built in, gives
   `publishAllPublicationsToDryRunRepository` as the plan names it; Central
   needs signing, javadoc jars and the portal upload on top, M10's work.
2. **`com.vanniktech.maven.publish`** — handles Central at M10, but adds a
   plugin and its defaults before anything is uploaded.

The `protocol` dependency:

3. **Publish it as `appmonitor-protocol`** (chosen) — a transitive artifact
   no app types; its iOS klibs are published too since the module has iOS
   targets, so the job needs macOS.
4. **Shade it into `appmonitor-core`** — one artifact fewer, but KMP has no
   per-target fat-jar support and `sdk/android-okhttp` also depends on it.

## Decision

Options 1 and 3. The convention plugin `appmonitor.publish` sets group and
version (`VERSION_NAME=0.1.0-SNAPSHOT` in `gradle.properties` until M10
picks the release), maps each module's `POM_ARTIFACT_ID` (its own
`gradle.properties`) onto every publication, fills name, description, URL,
MIT license, developer (no e-mail, as ADR-0055's `.deb`), SCM, and adds the
`dryRun` repository at `build/maven-dry-run`. `make publish-dry-run` runs
`scripts/publish_dry_run.sh`: it publishes, fails when any of the twelve
root, `-jvm` and `-android` POMs lacks one of those elements, lists the
files, and on macOS builds `build/spm-consumer` against the root
`Package.swift` by path in debug and release, printing size and
`SwiftProtobuf` symbol count. The release gate's `publish` job runs it on
macOS and uploads the repository for 7 days. It is not in `make check`.

## Consequences

- `make check` and PR CI unchanged; the gate gains one macOS job of ≈2 min
  (free on a public repository, ADR-0052).
- The root `Package.swift` lists the same sources as `sdk/ios/Package.swift`
  and `make swift` lints it; a change to targets or dependencies goes in
  both.
- A tag resolution (`.package(url:, from:)`) is not tried: there is no tag
  before M10.
- DEBT-0040 is re-triggered with the numbers above.
