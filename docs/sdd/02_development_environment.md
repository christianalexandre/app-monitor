# 02 — Development environment

What a machine needs to build and test the whole repository.

## Toolchain

| Tool | Version | Used by |
| --- | --- | --- |
| JDK | 21 | Gradle, desktop app |
| Xcode | 26.x | iOS SDK, iOS samples, simulator tests (macOS only) |
| XcodeGen | 2.46.0 tested, `brew install xcodegen` | `make swift-sim`, `make ios-sample`: the iOS test host and sample projects (ADR-0047, ADR-0050) |
| Android SDK | `ANDROID_HOME` (the `Makefile` defaults it to `~/Library/Android/sdk`), `platform-tools` and `emulator` on `PATH` | Android targets, emulator tests |
| GitHub CLI | authenticated | PRs from `/pr` and `/next` |
| GNU Make | any | command façade |
| jq | any | `make loop`, `make loop-watch` |
| Claude Code CLI | logged in | `make loop` |

No `protoc` is needed: Wire generates Kotlin without it (ADR-0001), and
`make swift-proto` builds `protoc` and `protoc-gen-swift` from the pinned
swift-protobuf for `sdk/ios` (ADR-0039). `make check` runs the Swift gates
(`make swift`) only on macOS with `swift` on the `PATH`; elsewhere it prints
`swift: SKIPPED` (ADR-0037). Inside `make swift`, the simulator tests
(`make swift-sim`) need XcodeGen and an iPhone simulator; without XcodeGen
they print `swift-sim: SKIPPED` (ADR-0047), and the sample build
(`make ios-sample`) `ios-sample: SKIPPED` (ADR-0050).
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
| `appmonitor.kmp-sdk` | Android/KMP SDK modules (`sdk/core`, `sdk/android-okhttp`, `sdk/no-op`) | `explicitApi()`; targets `jvm`, `android` (`minSdk` 24, host tests on); no iOS (ADR-0023); ABI dumps checked by `checkKotlinAbi` (ADR-0026) |
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

### The Android sample end to end, scripted

`make e2e-android` (ADR-0056) needs a booted emulator in `adb devices`
(`ANDROID_SERIAL` picks one when a phone is attached too) and the internet.
It builds the debug APK and the server's `installDist`, starts the headless
server on a free port, installs the APK, grants `ACCESS_LOCAL_NETWORK` on
API 37+, and launches the sample with launch extras:

```sh
adb shell am start -S -W --activity-clear-task -n appmonitor.sample/.MainActivity \
  --es host 10.0.2.2 --ei port <port> --ez runAll true
```

`host` and `port` restart the SDK on that desktop only; `runAll` runs every
button in turn. The script then waits up to `E2E_TIMEOUT` seconds (120) for
each call's `RequestStarted`, its response or failure, and the WebSocket's
sent, received and close frames, and prints one `ok` or `missing` line per
call. Server log and logcat in `build/e2e-android/`. A sample button added
or renamed needs its line in the script's expected list.

### The iOS sample against a desktop

`make ios-sample` builds `samples/ios` into `build/ios-sample/`. With
`make server` or `make desktop` running, install the Debug app on a booted
simulator and launch it with `-runAll YES`, which runs every button in turn;
the simulator finds the desktop on its host's loopback:

```sh
xcrun simctl install booted build/ios-sample/Build/Products/Debug-iphonesimulator/Sample.app
xcrun simctl launch booted dev.appmonitor.sample -runAll YES
```

For the screenshots, run
`./gradlew :desktop:app:liveScreenshots -PliveName=simulator -PliveExchanges=7`
in the background instead of the server (the seven buttons, one exchange
each), wait for "waiting for a device", then install and launch as above.
Calls made before the approval wait in the SDK's buffer, so `-runAll` can
start before the click. `-host H -port N` pin the SDK to that desktop, as
`make e2e-ios` does.

### The iOS sample end to end, scripted

`make e2e-ios` (ADR-0057) needs macOS with Xcode, XcodeGen and the
internet. It uses the booted iPhone simulator, or boots the first available
one (`SIM_DEVICE="iPhone 17"` picks by name), builds the sample through
`make ios-sample` and the server's `installDist`, starts the headless server
on a free port and launches the Debug app:

```sh
xcrun simctl launch <udid> dev.appmonitor.sample -host 127.0.0.1 -port <port> -runAll YES
```

It then waits up to `E2E_TIMEOUT` seconds (120) for the seven calls as
`make e2e-android` does (the upload's `PUT` and the background download
included; the WebSocket's URL is logged as `https://echo.websocket.org`).
Server log and the sample's simulator log in `build/e2e-ios/`. Both scripts
source `scripts/e2e_lib.sh`.

## CI

`.github/workflows/ci.yml` runs `make check` on `ubuntu-latest` with Temurin
21 for every PR to `dev` and every push to `dev` (the push run seeds the
Gradle cache PRs read). It uses the runner's preinstalled Android SDK
(`ANDROID_HOME` from the environment wins over the `Makefile` default) and
`setup-gradle` with `cache-provider: basic`. iOS tasks are skipped on Linux,
so on PRs to `dev` iOS simulator tests and `make swift` run only locally;
the release gate runs them. See
ADR-0012. `pr-conventions.yml` checks branch name, PR title, attribution
trailers and `make docs-check`.

`.github/workflows/release-gate.yml` runs on every PR to `main` and on
`workflow_dispatch` (ADR-0052). Its `branch` job runs
`scripts/branch_check.sh <head> main`, so only `dev` or `release/x.y.z` pass
(guidelines 03); every other job needs it. A dispatch checks its `head`
input (default `dev`) instead of a PR head, so the gate can be run on any
ref: `gh workflow run release-gate.yml --ref <branch> [-f head=<name>]`.
The workflow must exist on `dev` for a dispatch to find it. The `desktop`
job runs `:desktop:app:test`, `:desktop:server:test` and
`:protocol:jvmTest`, one step each, on ubuntu, macOS and windows
(`fail-fast: false`, reports uploaded on failure). On macOS it sets
`APPMONITOR_NO_LOCAL_NETWORK=1`, which drops the mDNS round trip test: the
hosted runner has no local network access (DEBT-0046). The `contract`
job, on macOS, installs XcodeGen with Homebrew, runs `make swift` (proto
freshness, swift-format, `swift test` with the fixture tests, public API,
simulator tests, sample) and `./gradlew :protocol:allTests`
(`iosSimulatorArm64Test` included); the simulator and sample logs and the
protocol reports are uploaded on failure. The `e2e-android` job, on ubuntu,
enables KVM, builds the debug APK and the server's `installDist`, then
boots an API 36 `google_apis` x86_64 emulator with
`reactivecircus/android-emulator-runner` and runs `make e2e-android` in it;
`build/e2e-android/` (server log, logcat) is uploaded on failure.
The `e2e-ios` job, on macOS, installs XcodeGen and runs `make e2e-ios`,
which boots the runner's first available iPhone simulator; the sample
reaches the server on `127.0.0.1`, so the runner's missing local network
access (DEBT-0046) does not apply. `build/e2e-ios/` (server and simulator
logs) and `build/ios-sample.log` are uploaded on failure.

## Limits

iOS targets build only on macOS. On Linux/Windows the desktop app and the
Android/JVM parts build normally. The desktop UI test renders offscreen and
needs no display.
