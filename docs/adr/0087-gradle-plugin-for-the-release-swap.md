---
id: ADR-0087
title: A Gradle plugin swaps the SDK for its no-ops in release builds
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0087 — A Gradle plugin swaps the SDK for its no-ops in release builds

## Context

A KMP app keeps the SDK out of its release builds with build logic it
copies from the README (ADR-0062 D.1, ADR-0073): the `shared` module reads
Xcode's `CONFIGURATION` (else `KOTLIN_FRAMEWORK_BUILD_TYPE`) to pick
`appmonitor-ktor` or `appmonitor-ktor-no-op`, and the Android app
substitutes `appmonitor-ktor` and `appmonitor-core` with their no-ops in
its `release*` configurations. That is about fifteen lines in two build
scripts, which a developer must understand to adapt (a `Staging`
configuration, a build type not named `release*`). The owner asked for a
plugin that does it instead. What an app developer types is a "stop and
ask" area (`AGENTS.md`, public API of an SDK); adding the plugin is also
product scope (`docs/plans/README.md` places a Gradle plugin in M9, for
injecting the OkHttp interceptor only).

Already decided, not reopened here:

- Release builds hold no SDK code: one no-op per adapter with the same
  public API, checked against committed ABI dumps (ADR-0072);
  `make kmp-sample` fails when a release binary names anything of the SDK
  but the no-op stubs (ADR-0073). One artifact disabled at run time was
  refused (ADR-0062 D.2).
- Native Android apps use `debugImplementation` / `releaseImplementation`
  (ADR-0017, ADR-0030); iOS apps get the swap from the Swift compiler
  (ADR-0048). Neither needs build logic beyond those lines.
- Maven group `io.github.christianalexandre`, one `X.Y.Z` tag for every SDK,
  publication in M10 (ADR-0054, ADR-0058).
- Why the swap sits in two modules: the AGP 9 KMP library plugin has one
  Android variant, and `kotlin("multiplatform")` cannot share a module with
  `com.android.application` (ADR-0073, evidence there).

Evidence, 2026-10-24:

- A plugin applied by id in `plugins {}` resolves through its marker
  artifact `plugin.id:plugin.id.gradle.plugin:plugin.version`; by default
  from the Gradle Plugin Portal, other repositories through
  `pluginManagement { repositories { } }`
  ([Gradle, Using plugins — intermediate](https://docs.gradle.org/current/userguide/plugins_intermediate.html)).
- "Dependency substitution rules allow for replacing project and module
  dependencies with specified alternatives", declared per configuration
  ([Gradle, Resolution rules](https://docs.gradle.org/current/userguide/resolution_rules.html)).
- The Kotlin Gradle plugin reads the build type of the framework Xcode
  builds from `CONFIGURATION`, else `KOTLIN_FRAMEWORK_BUILD_TYPE`
  (`XcodeEnvironment.buildType`, ADR-0073); the plugin can read it the
  same way, so the framework and the SDK pick always agree.

## Options

### A. How the release swap reaches the app

1. **A Gradle plugin, `io.github.christianalexandre.appmonitor`.** Applied
   in each module that depends on the SDK. The developer declares only the
   real artifact (`implementation("…:appmonitor-ktor:<v>")`); the plugin
   adds the substitution to every non-debuggable configuration on Android
   and to the iOS framework's dependencies when Xcode builds Release. The
   README snippet shrinks to one `plugins {}` line per module. Costs: a
   new published artifact with its own public API (id, extension), a
   compatibility range of AGP and the Kotlin Gradle plugin to test, and
   functional tests (Gradle TestKit) on a consumer project.
2. **Keep the documented snippets** (today, ADR-0073). No new artifact; the
   developer copies and owns the build logic, and an unusual build type is
   theirs to adapt.

Recommended (owner): 1.

### B. Where it is applied

1. **Each module that touches the SDK** — in a KMP app, `shared` and
   `androidApp`: two `plugins {}` lines. Uses only project APIs.
2. **Once, in `settings.gradle.kts`**, as a settings plugin that configures
   every project — one line; whether it can do so under Gradle's isolated
   projects is not verified.

Recommended: 1, 2 if M10 shows it works under isolated projects.

### C. Which builds count as release

1. **Android: every build type with `isDebuggable = false`; iOS: what the
   Kotlin Gradle plugin reads as the framework's build type** — a
   `staging` build type or a `Staging` configuration follows the same rule
   as the app's own code. An extension property
   (`appMonitor { forceNoOp = true }`) covers the rest.
2. **By name** (`release*`, `Release`) — what the snippets do today.

Recommended: 1.

### D. Native Android apps

1. **The plugin works there too, optional** — a native app can apply it
   instead of `debugImplementation` / `releaseImplementation`; the README
   keeps both, the plain lines first.
2. **KMP only** — native Android keeps its two lines.

Recommended: 1, so one rule decides "release" for every Gradle app.

### E. Publication and milestone

1. **Published to Maven Central in M10 with the SDK, same tag** — one
   account and one release; the consumer adds `mavenCentral()` to
   `pluginManagement` if it is not there. M9's OkHttp injection later
   lives in the same plugin. A task is added to PLAN for M10 when this is
   accepted; the samples apply it through `includeBuild`.
2. **Gradle Plugin Portal** — the default repository, no
   `pluginManagement` line; a second account and its review.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- With A.1 the KMP setup becomes: the dependency, one `plugins {}` line per
  module, `install(AppMonitorKtor)`, `AppMonitor.start`, the iOS Run Script
  phase (ADR-0049). The snippets of ADR-0073 stay in the SDD as what the
  plugin does.
- `make kmp-sample`'s release scan stays the proof; it runs on the samples
  built with the plugin instead of the hand-written swap.
- The plugin's id and extension join the public API that a later change
  must not break; its own ABI is dumped like the SDK's (ADR-0072).
