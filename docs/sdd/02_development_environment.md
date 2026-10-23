# 02 — Development environment

What a machine needs to build and test the whole repository.

## Toolchain

| Tool | Version | Used by |
| --- | --- | --- |
| JDK | 21 | Gradle, desktop app |
| Xcode | 26.x | iOS SDK, iOS samples, simulator tests (macOS only) |
| Android SDK | `ANDROID_HOME` (the `Makefile` defaults it to `~/Library/Android/sdk`), `platform-tools` and `emulator` on `PATH` | Android targets, emulator tests |
| GitHub CLI | authenticated | PRs from `/pr` and `/next` |
| GNU Make | any | command façade |
| jq | any | `make loop`, `make loop-watch` |
| Claude Code CLI | logged in | `make loop` |

No `protoc` is needed: Wire generates Kotlin without it (ADR-0001).
The Android SDK must provide platform `android-36` and build tools 36.0.0
(AGP 9.3, ADR-0007).

## Running things

The commands table in `AGENTS.md` is the source of truth and grows with each
module. Gradle comes from the wrapper (`./gradlew`, version pinned with a
checksum in `gradle/wrapper/gradle-wrapper.properties`); no local Gradle
install is needed. Library and plugin versions live in
`gradle/libs.versions.toml`. See ADR-0006.

Module scripts apply one convention plugin from `build-logic/`:

| Plugin id | For | Adds |
| --- | --- | --- |
| `appmonitor.lint` | every module (applied by the others) | Spotless + ktlint on `src/**/*.kt` and `*.gradle.kts` |
| `appmonitor.kotlin-jvm` | JVM-only modules (desktop, tools) | Kotlin JVM, toolchain 21, JUnit Platform |
| `appmonitor.kmp-library` | shared KMP code (protocol) | targets `jvm`, `android` (host tests on), `iosArm64`, `iosSimulatorArm64`; module sets `namespace` (ADR-0007) |
| `appmonitor.kmp-sdk` | Android/KMP SDK modules (`sdk/core`) | `explicitApi()`; targets `jvm`, `android` (`minSdk` 24, host tests on); no iOS (ADR-0023) |
| `appmonitor.android-app` | Android sample apps | AGP application, Compose compiler; `minSdk` 24, `compileSdk` / `targetSdk` 36 (ADR-0030) |
| `appmonitor.compose-desktop` | desktop UI modules | `kotlin-jvm` + Compose compiler + Compose Multiplatform |

## End to end with a real device

`./gradlew :desktop:app:liveScreenshots` (ADR-0031) runs the real
`AppMonitorServer` on port 47474 with the desktop window's composable rendered
offscreen. It waits up to 5 minutes for a device, screenshots the approval
panel, approves it, waits for 5 finished exchanges, then screenshots the GET
JSON and the WebSocket exchanges into `docs/screenshots/<liveName>-*.png`.
`make desktop` must not be running (same port). The sample is driven by hand:

1. `./gradlew :samples:android:assembleDebug`, then
   `adb -s <serial> install -r samples/android/build/outputs/apk/debug/android-debug.apk`.
2. Start the task in the background: `-PliveName=device` for a phone on the
   same Wi-Fi (mDNS); `-PliveName=emulator -PliveAdvertise=false` so the
   emulator has to fall back to `10.0.2.2`.
3. `adb shell am start -n appmonitor.sample/.MainActivity`; on Android 17+
   allow the local network prompt.
4. Tap the five buttons (`adb shell input tap`, coordinates from
   `adb shell uiautomator dump`); the task ends by itself.

Properties: `liveName`, `liveExchanges` (5), `liveTimeoutMs` (300000),
`liveAdvertise` (true). The buttons call public services, so the machine and
the device need internet.

## CI

`.github/workflows/ci.yml` runs `make check` on `ubuntu-latest` with Temurin
21 for every PR to `dev` and every push to `dev` (the push run seeds the
Gradle cache PRs read). It uses the runner's preinstalled Android SDK
(`ANDROID_HOME` from the environment wins over the `Makefile` default) and
`setup-gradle` with `cache-provider: basic`. iOS tasks are skipped on Linux,
so iOS simulator tests run only locally until the release gate (M3). See
ADR-0012. `pr-conventions.yml` checks branch name, PR title, attribution
trailers and `make docs-check`.

## Limits

iOS targets build only on macOS. On Linux/Windows the desktop app and the
Android/JVM parts build normally. The desktop UI test renders offscreen and
needs no display.
