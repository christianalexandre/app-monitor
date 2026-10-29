---
id: ADR-0157
title: The Gradle plugin adds the SDK, swaps it for the no-ops outside debug, and fails a release that keeps it
status: Accepted
date: 2026-10-28
superseded_by:
---

# ADR-0157 — The Gradle plugin adds the SDK, swaps it for the no-ops outside debug, and fails a release that keeps it

## Context

ADR-0093 decided that a Gradle plugin swaps the SDK for its no-ops (A.1),
is applied in each module that touches the SDK (B.1) and treats as release
every non-debuggable build type and the iOS framework's release build type
(C.1). The developer still declared the real artifact. The first field
test wrote that swap by hand: four catalog entries,
`debugImplementation`/`releaseImplementation`, 25 lines of
`dependencySubstitution` in the app, and an Xcode build type read in two
modules. The owner (2026-10-28): the Gradle setup must be adding the
plugin, and no SDK code may reach a production build.

- The SDK adapters expose the library they wrap with `api` (`okhttp`,
  `ktor-client-core`), so adding the OkHttp adapter to an app without
  OkHttp would bring OkHttp in, or raise the app's version.
- In the same build, a module coordinate is not swapped for this
  repository's projects, so the samples need another way to the local SDK.

## Options

1. **ADR-0093 as written.** The developer declares the artifacts per
   build type or per module; the plugin only substitutes.
2. **The plugin adds the SDK too.**
   - In an Android app, `appmonitor-core` and `appmonitor-okhttp`, with
     the adapter's `okhttp` excluded so the app keeps its own.
   - In a KMP module, `appmonitor-ktor` in `commonMain`.
   - In every non-debuggable variant of the app, each real module becomes
     its no-op, whichever module asked for it.
   - In a KMP module built by Xcode, the same swap applies unless the build
     type (`KOTLIN_FRAMEWORK_BUILD_TYPE`, else `CONFIGURATION`) is `debug`,
     so a configuration Kotlin cannot name gets the no-ops. Outside Xcode it
     applies when a requested task names `release`, `xcframework` or
     `publish`, so a release framework built by Gradle alone (an
     XCFramework on CI) has none of the SDK either.
   - A check task (`appMonitorCheck<Variant>`, before `pre<Variant>Build`;
     before each native target's link tasks, its own configuration only) fails the build
     when the release graph still resolves a real SDK module, for example
     a per-target artifact declared by hand.
   - The SDK version is the plugin's own, generated from the root's
     `VERSION_NAME`.
   - `appmonitor.sdk.local=true`, set in this repository only, makes the
     plugin use the `:sdk:*` projects.

## Decision

Option 2. The KMP `androidApp` and `shared` of `samples/kmp` and
`samples/android` apply only the plugin. ADR-0073's hand-written swap and
the README snippets of it are gone.

## Consequences

- Android setup is the `plugins {}` line and `AppMonitor.start`. In a KMP
  app, the line goes in the app and in each module that installs
  `AppMonitorKtor`.
- A release APK also carries `appmonitor-okhttp-no-op`'s three stubs
  (`kmp_sample_check.sh` allows them).
- An app without OkHttp holds the OkHttp adapter's classes without OkHttp.
  They are never loaded, and R8 drops them unreported (a minified release
  of such an app is in the TestKit tests).
- `appmonitor-okhttp` is now on the classpath of every app, so the Ktor
  plugin can no longer infer from it that `AppMonitorInterceptor` will see
  its requests. It marks a request only when its client runs on Ktor's
  OkHttp engine, the one engine that reaches the interceptor, which strips
  the mark. Another engine sends the request unmarked, and the mark never
  reaches a server (ADR-0129).
- The no-op targets of the substitution carry the plugin's version: Gradle
  refuses a target without one ("Must specify version for target of
  dependency substitution"). A TestKit consumer resolves the SDK from a
  Maven repository, as an app does.
- `./gradlew :gradle-plugin:functionalTest` builds consumers with stand-in
  SDK projects, and one with them published to a Maven repository: debug
  real, release no-op, no OkHttp brought to an app without it, the opt-out,
  and a release that resolves `appmonitor-core-android` failing. The plugin's ABI gains `NoSdkCheck`.
- Supersedes ADR-0093.
