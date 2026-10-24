---
id: ADR-0006
title: Gradle build with convention plugins, Spotless and ktlint
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0006 — Gradle build with convention plugins, Spotless and ktlint

## Context

Every Kotlin part of the repository (protocol, SDK core and adapters, desktop
modules, tools) builds with Gradle. Module scripts must stay short and
consistent, the Kotlin style must be enforced (guidelines 01), and versions
must come from an official source.

Evidence, checked on 2026-10-21:

- Latest Gradle is 9.8.2 (`services.gradle.org/versions/current`), but the
  Kotlin Gradle Plugin 2.4.20–2.4.21 is documented as compatible with Gradle
  up to **9.7.0** (`kotlin-web-site` `docs/v.list`, `maxGradleVersion`).
- Latest releases on GitHub: Kotlin 2.4.21, Compose Multiplatform 1.12.2,
  ktlint 1.8.0, Spotless Gradle plugin 8.10.4 (2026-10-08), ktlint-gradle
  14.2.0 (2026-03-12, before Gradle 9.7/9.8 shipped).

## Options

1. **Plain module scripts + `allprojects {}`** — no extra build; cross-project
   configuration breaks project isolation and the configuration cache.
2. **`build-logic` included build with precompiled script plugins** — one
   plugin per module kind; modules apply one id. Standard Gradle sample
   layout.
3. **Lint via ktlint-gradle** — dedicated ktlint plugin; last release predates
   the Gradle versions in use.
4. **Lint via Spotless running ktlint** — actively released, supports
   `.kt` and `.gradle.kts`, reads `.editorconfig`; can later host other
   formatters (Swift, YAML) behind one `spotlessCheck`.

## Decision

Option 2 for the build and option 4 for lint. Gradle wrapper pinned to
**9.7.1** (the newest patch of the newest minor that KGP 2.4.21 documents),
with `distributionSha256Sum`. Versions live in `gradle/libs.versions.toml`,
shared with `build-logic`. Conventions: `appmonitor.lint`,
`appmonitor.kotlin-jvm`, `appmonitor.kmp-library`,
`appmonitor.compose-desktop`. JVM toolchain 21; ktlint style
`ktlint_official`.

## Consequences

- A new module is one `plugins { id("appmonitor.<kind>") }` line plus its
  dependencies.
- Gradle upgrades wait for the Kotlin compatibility table, not for the Gradle
  release.
- The KMP convention has no Android target yet; it arrives with the first
  module that needs it (protocol, PLAN-0001 M0.2), together with AGP.
