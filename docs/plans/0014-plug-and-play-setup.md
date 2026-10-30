---
id: PLAN-0014
title: Plug and play setup
status: Done # Draft | Active | Done | Abandoned
milestone: H4
started: 2026-10-28
finished: 2026-10-30
---

# PLAN-0014 — Plug and play setup (H4)

## Goal

A team adds App Monitor to an existing app with **the dependency and one
start call**: on Android and KMP the Gradle plugin and `AppMonitor.start`;
on iOS the Swift package and `AppMonitor.start`, plus one Run Script
pasted from the README for physical devices. No SDK code reaches a
production build, and a build that would ship it fails.

The first field test (a KMP app, Ktor on Android and iOS, configurations
`Dev` and `Prod`) touched ten files and showed no traffic: the Swift SDK
was compiled out in `Dev`, by Xcode's name rule (ADR-0155), the Android client stopped itself
on the app's cleartext policy, and the release swap, the local network
permission and a second Kotlin start on iOS were all written by hand.

## Tasks

- [x] **H4.1 Android asks for its permission and stops blocking itself.** (#298)
  `AppMonitor.start(context)` asks for `ACCESS_LOCAL_NETWORK` on the first
  resumed Activity (Android 17+) through `ActivityLifecycleCallbacks`. The
  client no longer stops on the app's cleartext policy: CIO's sockets never
  obey it (ADR-0023). The README and `samples/android`, `samples/kmp` drop
  the debug network security config and `LocalNetwork.kt`. New ADR,
  superseding ADR-0017 E/F, ADR-0023's cleartext check and ADR-0132.
  Accept: `make e2e-android` and `make e2e-kmp-android` on an emulator,
  with the samples free of `LocalNetwork.kt`, their network security config limited to their own test target (ADR-0156); `./gradlew :sdk:core:allTests`.
- [x] **H4.2 The Gradle plugin adds the SDK and swaps it for the no-ops
  (ADR-0093, from M10).** (#298) Applied to an app or a KMP module, it adds the
  real SDK to debuggable variants and to debug Apple frameworks, the
  no-ops elsewhere (`KOTLIN_FRAMEWORK_BUILD_TYPE`, then `CONFIGURATION`),
  and **fails the build** when a non-debuggable variant or a release
  framework resolves a real SDK artifact. `samples/kmp` and
  `samples/android` lose their hand-written swap.
  Accept: `./gradlew :gradle-plugin:functionalTest`. A consumer with only
  `plugins {}` and `AppMonitor.start` has the SDK in debug and none in
  release (`dexdump`). A consumer forcing the real SDK into release fails.
  `make kmp-sample` passes its release scan.
- [x] **H4.3 KMP on iOS through the Swift SDK alone.** (#298) Ktor's Darwin engine
  runs on `URLSession`, and the Ktor plugin marks nothing until a Kotlin
  `AppMonitor.start` runs (ADR-0064). So an iOS app with the Swift SDK
  needs no Kotlin start and no `sdk/core` in `iosMain`. The README and
  `samples/kmp` drop both. New ADR.
  Accept: `make e2e-kmp-ios`, where each Ktor call shows once, from the
  Swift SDK's session only.
- [x] **H4.4 The app leaves the iOS SDK out of release builds
  (ADR-0155).** (#298) The package keeps no `#if DEBUG`. The app starts it under
  its own `#if DEBUG` and sets `EXCLUDED_SOURCE_FILE_NAMES = AppMonitor*`
  on each configuration that ships. The Run Script, inline in the README,
  leaves an excluded configuration as is, fails an optimized one that
  links the SDK, and adds the keys otherwise. ADR-0048 and ADR-0049
  superseded. SDD 01, 03, 06, 07 updated.
  Accept: `scripts/test_add_local_network_keys.sh` (excluded patterns,
  optimized linked builds failing, the README block equal to the
  script); `make swift`; `make ios-sample` and `make kmp-sample` with no
  SDK name in the Release app; `make swift-sim`.
- [x] **H4.5 Installation from the README alone.** (#317) The README's Usage
  becomes **Installation**: per platform, the dependency, the start call,
  then what is optional. Until M10, the steps to consume an unpublished
  SDK (`publishToMavenLocal`, the SwiftPM branch). The debug client logs
  once when what it needs is missing: no `NSBonjourServices` on a device,
  or the permission denied.
  Accept: fresh projects that follow only the README, an Android app
  (TestKit consumer) and an iOS app (XcodeGen, configurations `Dev` and
  `Prod`), build and send traffic in debug, and their release builds
  hold no SDK names; the step count per platform is listed in the PR.
- [x] **H4.7 OkHttp WebSocket frames through the plugin.** (#300) The plugin
  rewrites `OkHttpClient.newWebSocket` to ask the SDK first (ADR-0159), so
  an Android app drops `AppMonitorWebSocketFactory` and a KMP app on
  Ktor's OkHttp and Darwin engines installs nothing in `commonMain`.
  Accept: `./gradlew -p gradle-plugin check :gradle-plugin:functionalTest
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android` with
  no WebSocket line in the sample, its four frames asserted.
- [x] **H4.6 The iOS SDK without SwiftProtobuf.** (#301) The package pins
  `swift-protobuf` at `exact: 1.39.0`, so an app on another version cannot
  resolve both. Its runtime also stays in release builds (DEBT-0040). A
  minimal codec generated from `protocol/proto` replaces it (ADR-0160,
  superseding ADR-0039; ADR-0003 is unchanged). The gRPC adapter keeps
  SwiftProtobuf, with a version range, for the app's own messages.
  Accept: `make swift`, where `FixtureTests` decodes and re-encodes every
  fixture byte for byte; the root `Package.swift` with `swift-protobuf`
  only for `AppMonitorGRPC`; `make ios-sample` with no `SwiftProtobuf` symbol in
  the Release app; DEBT-0040 paid.

H4.1 to H4.4 landed in one PR (#298): the README, the ADR index and the
sample checks share their changes, and only the combined state was run.

The owner re-runs the field test in the same KMP app once H4.5 merges.
That is the plan's real check and not loop work: what it finds goes into
the Outcome.

### H4 milestone review

`code-review` at `medium` of `8ab8368..19f771a` (#298 to #317). Nine
findings, none that normal use hits:

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | `WriteQueue` drops a writer's items on an `Error` without counting them as lost. | No: needs an `Error` in the writer | DEBT-0109 |
| 2 | The SDK's permission request can meet the app's own one. | No: the app must ask while the SDK's dialog is up | DEBT-0109 |
| 3 | `answered` could log a denial when a rotation recreates the Activity under the dialog. | No: an Activity under the dialog is recreated but not resumed until the dialog goes | Rejected |
| 4 | Two quick proxy toggles can store the first value. | No: needs two concurrent toggles | DEBT-0109 |
| 5 | A CA past `notAfter` mints an expired leaf per handshake. | No: needs the desktop running past the CA's validity | DEBT-0109 |
| 6 | A response held early reports the compressed size. | No: needs a breakpoint on a compressed body past the capture limit | DEBT-0109 |
| 7 | `export_har` builds a page of frames before its size check. | No: needs frames of several MiB exported in full | DEBT-0109 |
| 8 | `ProtoWire` copies nested messages per level. | No: cost, not wrong data | DEBT-0109 |
| 9 | Comments cite `SDD NN`, which guideline 02 does not allow. | No | The two new ones fixed in this close; 85 older ones in DEBT-0109 |

## Outcome

- **Shipped**:
  - H4.1 to H4.4 in one PR (#298): Android asks for its permission and ignores the
    cleartext policy, the Gradle plugin adds the SDK and swaps it for the
    no-ops, KMP on iOS through the Swift SDK alone, and iOS release builds
    exclude the SDK.
  - H4.7: OkHttp WebSocket frames through the plugin (#300).
  - H4.6: the iOS SDK without SwiftProtobuf (#301).
  - H4.5: installation from the README alone, logs for missing setup
    (#317).
  - Field test follow-ups and two review rounds outside the tasks: Run
    Script troubleshooting (#302, #306), no SDK in KMP release binaries
    or dynamic features (#303, #315), the protobuf codec per spec and a
    cross-language fuzz (#304, #308), Ktor's OkHttp engine known by class
    (#305), one permission request with two screens (#307), breakpoint,
    body and mock fixes in the SDKs (#309, #310, #311), desktop bounds
    and proxy fixes (#312, #313), duplications recorded (#314).
- **Decisions**: ADR-0155 (the app leaves the iOS SDK out of release),
  ADR-0156 (Android asks for its permission, ignores cleartext), ADR-0157
  (the plugin adds the SDK, fails a release keeping it), ADR-0158 (KMP on
  iOS by the Swift SDK alone), ADR-0159 (WebSockets by the plugin),
  ADR-0160 (protocol without SwiftProtobuf), ADR-0161 (release binaries
  and dynamic features hold no SDK), ADR-0162 (install from Maven Local
  and `dev` until M10, setup logs).
- **Debt**:
  - Paid: DEBT-0040 (#301), DEBT-0054 (#317), DEBT-0089.
  - Created: DEBT-0099 to DEBT-0108, DEBT-0110 (a CI timeout in #317),
    and DEBT-0109 (this review's findings 1, 2 and 4 to 9).
  - No correctness finding that normal use hits is left open.
- **Deviations**:
  - H4.1 to H4.4 merged as one PR, because they share the README and the
    sample checks.
  - H4.6 and H4.7 were added during the plan, after the field test.
  - H4.5 found that AGP 9's built-in Kotlin 2.2 cannot compile against
    the SDK (DEBT-0108), and restored `make swift-sim`, broken since #309.
- **Field test** (2026-10-30, `dev` at `19f771a`, run by the agent at the
  owner's request): the owner's KMP app (Mibo: Ktor, the Tuya SDK on
  OkHttp, Firebase, configurations `Dev` and `Prod`) set up from the
  README alone, two steps on Android and four on iOS; SDK and plugin
  from `publishToMavenLocal` as the README says, no init script.
  - Android, a Galaxy S10 (Android 12) on Wi-Fi with `make server`:
    the app found the desktop by mDNS and sent the Tuya SDK's calls with
    no code beyond the start. `devRelease` holds only the no-op stubs
    (`dexdump`: 8 classes, `AppMonitor` with an empty body; no
    `_appmonitor._tcp` string).
  - iOS, the iPhone 17 Pro simulator: `Dev` shows the Kotlin code's Ktor
    calls and Firebase's once each, from the Swift SDK alone. `Prod`
    holds no `AppMonitor` symbol, and the Run Script left it as is.
  - `samples/android` on the same S10, by mDNS: every call and the
    WebSocket's frames. `make e2e-ios`, `make e2e-kmp-ios` (simulator),
    `make e2e-android` and `make e2e-kmp-android` (emulator) passed.
  - No setup log on either platform, as nothing was missing.
  - Found: Xcode kept the first `dev` commit (`19717d7`, #301) after the
    `Package.resolved` was deleted; the README now says how to update.
  - Not covered: the flows after login (the owner's account), a
    physical iPhone (PLAN-0015 H5.6), the desktop window (the field test
    used `make server`).
- **Lessons**:
  - A fresh project written from the README alone found what the samples
    hide (the Kotlin floor, the missing imports). Keep one for each
    install change.
  - `make swift-sim` is not in `make check`, so a broken TestHost went
    unseen for eight PRs. A PR that changes an internal API the TestHost
    uses should run it.
