---
id: ADR-0161
title: KMP release binaries link the no-ops at their link task, and dynamic features hold no SDK
status: Accepted
date: 2026-10-29
superseded_by:
---

# ADR-0161 — KMP release binaries link the no-ops at their link task, and dynamic features hold no SDK

## Context

ADR-0157 has the Gradle plugin add the SDK, swap it for the no-ops outside
debug and fail a release that keeps it. Already decided and not reopened
here: the Android side (every non-debuggable variant swapped, its
`runtimeConfiguration` and `compileConfiguration`), `appmonitor-ktor` added
to a KMP module's `commonMain`, the check task, the plugin's own SDK
version, and `appmonitor.sdk.local=true`. Standing owner decision: **debug
only**, release builds hold no SDK code.

For a KMP module, ADR-0157 decides the build type once per Gradle run, for
every configuration of the module: `KOTLIN_FRAMEWORK_BUILD_TYPE`, else
`CONFIGURATION`, else `release` when a requested task names `release`,
`xcframework` or `publish`. Measured on `samples/kmp/shared` with
`./gradlew :samples:kmp:shared:dependencies --configuration
iosSimulatorArm64CompileKlibraries` (review of 2026-10-29):

- `CONFIGURATION=Release KOTLIN_FRAMEWORK_BUILD_TYPE=debug` resolves the real
  `:sdk:ktor`. Kotlin reads the two the other way around: `CONFIGURATION`
  when it is exactly `debug` or `release`, else `KOTLIN_FRAMEWORK_BUILD_TYPE`
  (KGP 2.4.21 `XcodeEnvironment.kt`, `buildType`). With the same
  environment, `embedAndSignAppleFrameworkForXcode --dry-run` runs
  `linkReleaseFrameworkIosSimulatorArm64` and no `appMonitorCheck*`. So the
  release framework held the real SDK and nothing failed.
- `./gradlew :samples:kmp:shared:assemble` (or `build`) links
  `linkReleaseFramework*` with no swap and no check, since no task name
  says `release`.
- `CONFIGURATION=Dev` without `KOTLIN_FRAMEWORK_BUILD_TYPE`, as CocoaPods
  runs it (its build type comes from `xcodeConfigurationToNativeBuildType`,
  KGP `KotlinCocoapodsPlugin.kt`), gets the no-ops in the debug framework:
  no capture in `Dev`.

Kotlin compiles a target's klib once and links each binary from it. The
binary's link task (`KotlinNativeLink`) knows its build type and takes its
klibs from a `ConfigurableFileCollection`, `libraries` (KGP 2.4.21
`KotlinNativeLink.kt`). The no-ops have the real modules' klib ABI
(ADR-0072, `:sdk:no-op:check`), and `sdk/ktor` has no public `inline`
function, so nothing of it is inlined into the app's klib.

A dynamic feature module (`com.android.dynamic-feature`) resolves the base
app's dependencies in its own configurations, where the app's swap does not
apply, and AGP leaves out of the feature only what the base APK packages.
Measured with a TestKit consumer (`OkHttpInjectionTest`, a base app with
the plugin and one feature that declares nothing): the feature's release
APK held `appmonitor.sdk.core.RealCore` and `AppMonitorOkHttp`, beside a
base release APK with the no-ops. A feature variant has no `debuggable` of
its own (AGP 9.3.3 `DynamicFeatureVariant`).

## Options

1. **Keep one build type per run, read as Kotlin reads it.** Fixes the
   order of the two variables. `assemble` still links release frameworks
   with the SDK. Failing those links instead would fail `./gradlew build` in
   every KMP app that applies the plugin. CocoaPods' mapping would need its
   DSL read too.
2. **Swap at each release binary's link.** Every `NativeBuildType.RELEASE`
   binary links a copy of its compilation's klibs with the no-ops
   substituted, and `appMonitorCheck<LinkTask>` runs before it on that copy.
   A release library's `export` configuration gets the swap too. Debug
   binaries link the real SDK. No environment variable or task name is read.
   Debug and release binaries can come out of one run, each with its own
   SDK.

3. **Dynamic features: swap them as the base app.** Needs each feature
   variant to know whether its base variant is debuggable, which AGP does
   not expose there, and the plugin applied in every feature module.
4. **Dynamic features: leave every SDK module out of their runtime
   classpaths.** The base APK always carries the SDK or its no-ops, the same
   API, so a feature finds its classes there in debug and in release. The
   base app's plugin does it for each path in `dynamicFeatures`; the feature
   modules apply nothing.

## Decision

Options 2 and 4. Kotlin's own choice of link task decides, for Xcode,
CocoaPods, XCFrameworks and plain Gradle alike; a dynamic feature never
packages the SDK. The rest of ADR-0157,
listed in Context, holds unchanged; this ADR supersedes it.

## Consequences

- `CONFIGURATION`, `KOTLIN_FRAMEWORK_BUILD_TYPE` and task names no longer
  matter to the plugin. A configuration such as `Dev` gets the SDK when
  Kotlin builds it as debug, by `KOTLIN_FRAMEWORK_BUILD_TYPE` or a CocoaPods
  mapping.
- The KMP module's klib compilation and its Android configurations are no
  longer swapped. The Android app's variants still are (ADR-0157).
- The plugin compiles against KGP (`compileOnly`), as against AGP. The app's
  own KGP is the one at run time.
- `KmpReleaseLinkTest` (TestKit, host native target): debug and release
  executables from one run under `CONFIGURATION=Release
  KOTLIN_FRAMEWORK_BUILD_TYPE=debug`, `assemble` under `CONFIGURATION=Dev`,
  and a release link failing on a real per-target artifact declared by hand.
- The base app's plugin configures its feature modules' runtime classpaths,
  which ties them to it at configuration time (not compatible with Gradle's
  isolated projects, which this build does not use).
- `OkHttpInjectionTest`: a base app and a feature, debug and release, the
  SDK in the base APK only.
- `NoSdkCheck.configurationName` holds the link task's or the variant's
  name.
- A release link keeps the libraries Kotlin gives it (the stdlib and the
  platform klibs) minus the compilation's configuration, plus that
  configuration's swapped copy.
- The exclusion names each SDK module, real and no-op, in the SDK's group,
  so a library of another group with the same module name (`core`) stays.
  A feature's test classpaths keep the SDK: no APK ships them.
- Supersedes ADR-0157.
