---
id: ADR-0054
title: SDK publication coordinates and tag scheme
status: Proposed # Proposed | Accepted | Superseded | Rejected
date: 2026-10-23
superseded_by:
---

# ADR-0054 — SDK publication coordinates and tag scheme

## Context

PLAN-0004 M3.2 (c), needed by M3.10's dry-run: what an app developer types
to add the SDKs. M3 publishes to a local repository only; Maven Central and
SwiftPM tags are M10 (roadmap, `docs/plans/README.md`). Already decided and
not reopened: the modules `sdk/core`, `sdk/android-okhttp`, `sdk/no-op`
(ADR-0026, ADR-0030), the Swift package `AppMonitor` with one library
product `AppMonitor` (ADR-0039), Kotlin packages `appmonitor.*`.

Evidence, read on 2026-10-23:

- No `group` is set in any `build.gradle.kts`; Android namespaces are
  `appmonitor.sdk.core`, `appmonitor.sdk.okhttp`, `appmonitor.sdk.noop`;
  the iOS sample and test host use the bundle prefix `dev.appmonitor`.
- `appmonitor.dev` is a registered domain (`dig NS appmonitor.dev` →
  Cloudflare name servers); nothing in the repository shows the owner
  holds it. Maven Central only accepts a namespace whose domain the
  publisher proves, or `io.github.<login>` for a GitHub account
  ([Sonatype namespaces](https://central.sonatype.org/register/namespace/)).
- `Package.swift` is in `sdk/ios/`, not at the repository root. A probe
  under `build/spm-probe` (a git repository depended on by `file://` URL,
  `swift package resolve`, Swift 6.2) showed: with the manifest in a
  subdirectory, "the package manifest at '/Package.swift' cannot be
  accessed"; with it at the root, tag `v1.1.0` resolved as version 1.1.0,
  while tag `ios/1.5.0` was not a version ("no versions … match the
  requirement 1.5.0..<2.0.0").
- Sample apps today add `debugImplementation(project(":sdk:android-okhttp"))`
  and `releaseImplementation(project(":sdk:no-op"))`.

## Options

Maven group:

1. **`io.github.christianalexandre`** (recommended) — verifiable on Central
   with the GitHub account alone; long, tied to the login.
2. **`dev.appmonitor`** — matches the bundle prefix; needs the
   `appmonitor.dev` domain, which the owner may not hold.

Artifact ids (with either group): `appmonitor-core`,
`appmonitor-okhttp`, `appmonitor-no-op` — the prefix keeps them
recognisable in a dependency list where the group is long. An app writes
`debugImplementation("<group>:appmonitor-okhttp:<v>")` and
`releaseImplementation("<group>:appmonitor-no-op:<v>")`.

SwiftPM:

3. **Root `Package.swift`** (recommended) — a manifest at the repository
   root whose target points at `sdk/ios/Sources/AppMonitor`; the app adds
   `https://github.com/christianalexandre/app-monitor` and the product
   `AppMonitor`. Cloning the package clones the whole monorepo.
4. **A mirror repository** (`app-monitor-ios`) pushed by the release — a
   small clone for apps, one more repository and a sync step to keep green.

Tag scheme:

5. **One version for every SDK, tag `X.Y.Z`** (recommended) — the only
   form SwiftPM resolves from this repository; Kotlin and Swift SDKs then
   release together, even when only one changed.
6. **Per-SDK versions** — `X.Y.Z` for iOS, `kotlin-X.Y.Z` for Maven;
   independent releases, two version lines to document.

## Decision

Empty while `Proposed`: these are the names an app developer types, i.e.
the SDKs' public API (`AGENTS.md`).

## Consequences

- With Option 3, M3.10 adds the root manifest and the SwiftPM consumer
  resolves it by path; `swift test --package-path sdk/ios` keeps working.
- Remote SwiftPM resolution and Maven Central need the repository to be
  public or the consumer to have access; that is M10's question.
- DEBT-0040 is not decided here: M3.10 measures it.
