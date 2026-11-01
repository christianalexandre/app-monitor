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
`make swift-proto` writes `sdk/ios`'s types with `scripts/swift_proto_gen.py`,
`python3` only (ADR-0160). `make check` is the fast suite
(ADR-0076): docs, lint, script tests, `./gradlew build` without the sample
apps, and the host Swift gates (`make swift`) only on macOS with `swift` on
the `PATH`; elsewhere it prints `swift: SKIPPED` (ADR-0037). The slow
suites are separate targets: `make swift-sim` (simulator tests: the
package's suite, then the hosted ones, on the iOS `SIM_OS` picks, ADR-0170) and
`make samples` (`android-samples`, `ios-sample`, `kmp-sample`);
`make check-full` runs `make check` plus both. The simulator targets need
XcodeGen and an iPhone simulator; without them they print
`swift-sim: SKIPPED` (ADR-0047) and `ios-sample: SKIPPED` (ADR-0050).
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
| `appmonitor.kmp-library` | shared KMP code (protocol) | targets `jvm`, `android` (host tests on), `iosArm64`, `iosSimulatorArm64`; module sets `namespace` (ADR-0007); language and API version 2.3, as `appmonitor.kmp-sdk` (ADR-0169) |
| `appmonitor.kmp-sdk` | Android/KMP SDK modules (`sdk/android-okhttp`, `sdk/okhttp-no-op`; through `kmp-sdk-ios`, the others) | `explicitApi()`; targets `jvm`, `android` (`minSdk` 24, host tests on) (ADR-0023); ABI dumps checked by `checkKotlinAbi` (ADR-0072); language and API version 2.3, `kotlin-stdlib` 2.3.21 in the POMs, `kotlin.uuid` opted in, so an app on Kotlin 2.2 compiles against it (ADR-0169) |
| `appmonitor.kmp-sdk-ios` | SDK modules a KMP app links on iOS (`sdk/core`, `sdk/ktor`, `sdk/core-no-op`, `sdk/ktor-no-op`) | `appmonitor.kmp-sdk` plus `iosArm64`, `iosSimulatorArm64` (ADR-0062 E.1, ADR-0067); the klib dump `api/<module>.klib.api` |
| `appmonitor.android-app` | Android sample apps | AGP application, Compose compiler; `minSdk` 24, `compileSdk` / `targetSdk` 36 (ADR-0030) |
| `appmonitor.compose-desktop` | desktop UI modules | `kotlin-jvm` + Compose compiler + Compose Multiplatform |

## End to end with a real device

`./gradlew :desktop:app:liveScreenshots` (ADR-0031) runs the real
`AppMonitorServer` on port 47474 with the desktop window's composable rendered
offscreen, on a store, approvals and `Recorder` like `Main.kt`'s. It clicks
Record, waits up to 5 minutes for a device, screenshots the approval panel,
approves it (the answer is stored), waits for 5 finished exchanges, then
screenshots the GET JSON and the WebSocket exchanges from the live list and
clicks Stop. It then reopens the store and screenshots the GET JSON from the
recording (`<liveName>-reopen.png`). All go into `docs/screenshots/<liveName>-*.png`. `make desktop` must not be
running (same port). The sample is driven by hand:

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
`liveAdvertise` (true), `liveDataDir` (a temporary directory; an empty one
for the approval to be asked). The buttons call public services, so the machine and
the device need internet.

### The Android sample end to end, scripted

`make e2e-android` (ADR-0056) needs a booted emulator in `adb devices`
(`ANDROID_SERIAL` picks one when a phone is attached too); the internet only
for the first build. It builds the debug APK and the `installDist` of the
server and of `tools/e2e-target`, starts both on free ports, installs the
APK, grants `ACCESS_LOCAL_NETWORK` on API 37+, and launches the sample with
launch extras:

```sh
adb shell am start -S -W --activity-clear-task -n appmonitor.sample/.MainActivity \
  --es host 10.0.2.2 --ei port <port> --es target http://10.0.2.2:<target port> --ez runAll true
```

`tools/e2e-target` (ADR-0074) serves, on `127.0.0.1` only, the httpbin.org
paths the samples call and a WebSocket echo on `/ws`; `target` replaces
`https://httpbin.org` and `wss://echo.websocket.org` with it. Without
`target` the samples call the public services.

The server is started with `--mocks build/e2e-android/mocks.json`, one
rule (id 1) for `GET 10.0.2.2/status/418` that answers 200 `mocked`; the
server sends it to the device after `HelloAck` (ADR-0116). The sample's last
button, `418, or a mock`, calls that path, and its line asserts the
`RequestStarted` carries `mock 1`: the SDK answered it without the network.
`--replay /json` makes the server ask the device to send the first
`GET /json` again; that line asserts a `RequestStarted` with `replay_of`
its request id and a `ResponseReceived` (ADR-0122 D.1). The check for
duplicates leaves replays out.

`host` and `port` restart the SDK on that desktop only, and again only when
they change, so a rotation keeps the connection, a new launch on another port
moves to it and a process death reconnects to the same desktop; `runAll`
runs every button in turn, on first launch only. The script then waits up to
`E2E_TIMEOUT` seconds (120) for each call's `RequestStarted`, its response or
failure, and the WebSocket's sent, received and close frames, and prints one
`ok` or `missing` line per call, and fails when a request went anywhere but
the target or an expected request was captured twice (ADR-0075). Server log, target log and logcat in `build/e2e-android/`. A sample button added or renamed needs its line in the
script's expected list.

### The iOS sample against a desktop

`make ios-sample` builds `samples/ios` into `build/ios-sample/`, from
`samples/ios/project.yml`, without gRPC-Core; `samples/ios/grpc.yml`
includes that spec and adds gRPC-Core and its button in Debug
(`SampleGrpc.xcodeproj`, `SAMPLE_GRPC`), built by `make e2e-ios-grpc` into
`build/ios-sample-grpc/` (ADR-0163). With
`make server` or `make desktop` running, install the Debug app on a booted
simulator and launch it with `-runAll YES`, which runs every button in turn;
the simulator finds the desktop on its host's loopback:

```sh
xcrun simctl install booted build/ios-sample/Build/Products/Debug-iphonesimulator/Sample.app
xcrun simctl launch booted dev.appmonitor.sample -runAll YES
```

For the screenshots, run
`./gradlew :desktop:app:liveScreenshots -PliveName=simulator -PliveExchanges=8`
in the background instead of the server (the eight `URLSession` buttons, one
exchange each; the gRPC build's ninth, `gRPC-Core unary`, is not captured by the SDK), wait for "waiting for a device", then install and launch as above.
Calls made before the approval wait in the SDK's buffer, so `-runAll` can
start before the click. `-host H -port N` pin the SDK to that desktop, as
`make e2e-ios` does.

### The iOS sample end to end, scripted

`make e2e-ios` (ADR-0057) needs macOS with Xcode and XcodeGen; the
internet only for the first build. It uses the booted iPhone simulator, or boots the first available
one (`SIM_DEVICE="iPhone 17"` picks by name), builds the sample's Debug app
without gRPC-Core for it (Release and its SDK name scan are `make ios-sample`'s, in
`make samples`; ADR-0075) and the `installDist` of the server and of
`tools/e2e-target`, starts both on free ports, and launches the Debug app:

```sh
xcrun simctl launch <udid> dev.appmonitor.sample -host 127.0.0.1 -port <port> \
  -target http://127.0.0.1:<target port> -runAll YES
```

The server gets `--mocks build/e2e-ios/mocks.json`, one rule (id 1) for
`GET 127.0.0.1/status/418` that answers 200 `mocked`, as on Android; the
sample's `418, or a mock` button calls that path (ADR-0117). With
`--replay /json` it asks the device to send the sample's first call again,
through the app's `URLSession` (ADR-0124).
It then waits up to `E2E_TIMEOUT` seconds (120) for the eight calls as
`make e2e-android` does (the upload's `PUT`, the background download and
the mocked call, asserted `mock 1`, and `GET /json`'s replay, asserted `replay`, included; the WebSocket's URL is logged as `http://127.0.0.1:<target port>/ws`).
From an empty `build/ios-sample/` it took 28 s on an M-series Mac.

`make e2e-ios-grpc` (`scripts/e2e_ios.sh --grpc`, ADR-0163) does the same
with the gRPC build of the sample (gRPC-Core's C++ makes it about four
minutes cold here, ADR-0109), logs in `build/e2e-ios-grpc/`: it starts the
target with its TLS gRPC origin (`--grpc-trust`) and the server with its
proxy (`--proxy`, trusting that origin), and launches the app through the
proxy (ADR-0108):

```sh
SIMCTL_CHILD_grpc_proxy=http://127.0.0.1:<proxy port> \
  SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH=<repo>/build/e2e-ios-grpc/proxy/proxy-ca.pem \
  xcrun simctl launch <udid> dev.appmonitor.sample -host 127.0.0.1 -port <port> \
  -target http://127.0.0.1:<target port> -grpcTarget 127.0.0.1:<grpc port> -runAll YES
```

It asserts the eight calls above and the gRPC-Core call in the `[proxy]`
session: `POST https://127.0.0.1:<grpc port>/grpcbin.GRPCBin/DummyUnary`,
its request and response messages, its response.
Server log, target log, the sample's simulator log and `build.log` in `build/e2e-ios/`. The three
end to end scripts source `scripts/e2e_lib.sh` (device pick, server and target, assertions),
whose assertions `scripts/test_e2e_lib.sh` tests on a crafted log in `make scripts-test`.

### The KMP sample against a desktop

`make kmp-sample` (ADR-0073, in `make samples`) builds `samples/kmp`: the
Android app in debug and release, then, on macOS with XcodeGen, the iOS host
in Debug and Release into `build/kmp-sample/`; its Run Script phase calls
`./gradlew :samples:kmp:shared:embedAndSignAppleFrameworkForXcode`, which
needs a JDK on the `PATH` Xcode sees. Release links `sdk/ktor-no-op` and
`sdk/core-no-op` on both platforms; the script fails when a release holds
any other SDK name. Log in `build/kmp-sample.log`. With `make server` or
`make desktop` running, the same launch paths as the other samples:

```sh
adb install -r samples/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -S -W -n appmonitor.sample.kmp/.MainActivity --es host 10.0.2.2 --ez runAll true
xcrun simctl install booted build/kmp-sample/Build/Products/Debug-iphonesimulator/KmpSample.app
xcrun simctl launch booted dev.appmonitor.kmpsample -host 127.0.0.1 -runAll YES
```

`--es target URL` on Android and `-target URL` on iOS point the calls at
`tools/e2e-target`, as for the other samples. The five calls (GET, POST,
404, timeout, WebSocket echo) go through Ktor
(OkHttp on Android, Darwin on iOS). On iOS only the Swift SDK runs: the
desktop shows one session of the app, the Swift SDK's, with each exchange
once (ADR-0158).

### The KMP sample end to end, scripted

`make e2e-kmp` (ADR-0075) runs `make e2e-kmp-android` then
`make e2e-kmp-ios`, both `scripts/e2e_kmp.sh`. Each needs what its
counterpart above needs (a booted emulator; macOS with Xcode and XcodeGen),
builds only the sample's debug app (Release and the SDK name scan stay in
`make kmp-sample`, as for `make e2e-ios`), starts the server and `tools/e2e-target` on free ports
and launches the sample with `host`, `port`, `target` and `runAll`, as
`make e2e-android` and `make e2e-ios` do. It asserts the five calls; the
WebSocket's URL is logged as `ws://<host>:<target port>/ws` on Android,
Ktor's own, and `http://…` on iOS, as `URLSession` reports it. "Captured
once" compares URLs with `ws` as `http` and `wss` as `https`. On iOS it
also fails unless `dev.appmonitor.kmpsample` opened a session with
`sdk_name` `swift` and none with `kotlin` (ADR-0077, ADR-0158).
Logs in `build/e2e-kmp-android/` and `build/e2e-kmp-ios/` (`build.log`
holds the iOS build).

## Desktop packages

`./gradlew :desktop:app:packageDmg` (macOS), `packageMsi` (windows) or
`packageDeb` (linux) builds an installer for the host OS only, with
jpackage from the JDK 21 toolchain (the Compose plugin downloads WiX for
`.msi`); output in `desktop/app/build/compose/binaries/main/<format>/`, e.g.
`App Monitor-1.0.0.dmg` (≈75 MB, ≈2.5 min cold). Names, bundle id
`io.github.christianalexandre.appmonitor.desktop`, Linux package
`app-monitor` and the Windows `upgradeUuid` are ADR-0055's; the bundled
runtime adds the modules `./gradlew :desktop:app:suggestRuntimeModules`
prints, so rerun it after adding a dependency. The packages are **unsigned
build proof**, not releases: macOS Gatekeeper and Windows SmartScreen block
them until the user overrides (ADR-0055). The app inside listens on 47474
like `make desktop`.

To check the packaged app on a store a live run left (no Gatekeeper prompt,
no copy to `/Applications`): mount the image with
`hdiutil attach -readonly -nobrowse -mountpoint build/live/dmg <dmg>` and run
`APPMONITOR_DATA_DIR=<liveDataDir> "build/live/dmg/App Monitor.app/Contents/MacOS/App Monitor"`.
The launch opens a run of its own in the `runs` table, a device approved in
the live run connects with no prompt and its exchanges land under that run;
`sqlite3 <liveDataDir>/appmonitor.db` reads them, and a SIGTERM closes the run
and the sessions. `hdiutil detach build/live/dmg` afterwards.

## MCP clients against real traffic

The setup a developer follows is the README's *Ask your AI agent (MCP)*
(ADR-0091). To check every tool against a sample's traffic stored by
`make desktop`, with no clicks:

1. A store with the device approved: `liveScreenshots` with
   `-PliveDataDir=$PWD/build/<dir>/data` (an absolute path), as above. It
   approves the **first** device that says hello; a sample already running
   on the emulator wins over a simulator launched after it.
2. `APPMONITOR_DATA_DIR=$PWD/build/<dir>/data make desktop`, then launch
   the approved sample with `runAll`: no prompt, its exchanges land in the
   live list, which the tools read without `run_id`.
3. The token is in the store:
   `sqlite3 build/<dir>/data/appmonitor.db "select value from settings where key='mcp.token'"`.
4. MCP Inspector (`npx @modelcontextprotocol/inspector --cli <url> --header
   "Authorization: Bearer <token>" --method tools/call --tool-name <tool>
   --tool-arg key=value`), or Claude Code without touching its own
   configuration: the README's `.mcp.json` saved as `build/<dir>/mcp.json`,
   then `APPMONITOR_MCP_TOKEN=<token> claude -p --strict-mcp-config
   --mcp-config build/<dir>/mcp.json --allowedTools mcp__app-monitor "<question>"`.
5. A SIGTERM to the app's `java` process stops it and closes the run; the
   Gradle `run` task then reports `BUILD FAILED`, which is expected.

## Traffic through the proxy

The proxy (SDD 11) is off until its switch in the settings is on. Then,
with `make desktop` running:

- **curl**: `curl --proxy http://127.0.0.1:47476 --cacert "<data
  dir>/proxy-ca.pem" https://example.com` — the exchange shows in the
  `Proxy` session.
- **A simulator app** (ADR-0108): the settings' launch command,
  `SIMCTL_CHILD_grpc_proxy=http://127.0.0.1:47476
  SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH="<data dir>/proxy-ca.pem"
  xcrun simctl launch booted <bundle id>`; from Xcode, `grpc_proxy` and
  `GRPC_DEFAULT_SSL_ROOTS_FILE_PATH` in the scheme's environment. Only
  gRPC-Core reads them; `URLSession` stays on the SDK's capture. The iOS
  sample's `gRPC-Core unary` button calls `grpcb.in:9001` over TLS
  (`-grpcTarget host:port` replaces it), so launching it this way shows
  the call in the `Proxy` session.

## SDK publication dry-run

`make publish-dry-run` (`scripts/publish_dry_run.sh`, ADR-0058) publishes
`sdk/core`, `sdk/android-okhttp`, `sdk/ktor`, their no-op twins and the
`protocol` they depend on to `build/maven-dry-run`, group
`io.github.christianalexandre`, artifacts `appmonitor-core`,
`appmonitor-okhttp`, `appmonitor-ktor`, `appmonitor-core-no-op`,
`appmonitor-okhttp-no-op`, `appmonitor-ktor-no-op`, `appmonitor-protocol`
with their `-jvm` and `-android` variants, and `-iosarm64` and
`-iossimulatorarm64` for all but the OkHttp ones (ADR-0054, ADR-0062),
version `VERSION_NAME` from `gradle.properties`; it fails when a POM lacks
name, description, URL, license, developer or SCM, or when a no-op POM
depends on an `appmonitor-*` artifact that is not a no-op (ADR-0072). On macOS it then builds
`build/spm-consumer`, a throwaway executable that adds the root
`Package.swift` by path, in debug and release, and prints the binary size
and `SwiftProtobuf` symbol count, 0 for the `AppMonitor` product (ADR-0160). ≈35 s cold. Nothing is
uploaded anywhere: Central and tags are M10.

## CI

`.github/workflows/ci.yml` runs `make check` on `ubuntu-latest` with Temurin
21 for every PR to `dev` and every push to `dev` (the push run seeds the
Gradle cache PRs read). It uses the runner's preinstalled Android SDK
(`ANDROID_HOME` from the environment wins over the `Makefile` default) and
`setup-gradle` with `cache-provider: basic`. iOS tasks are skipped on Linux,
so on PRs to `dev` `make swift` runs only locally; simulator tests, sample
apps and end to ends run in the release gate (ADR-0076). See
ADR-0012. `pr-conventions.yml` checks branch name, PR title, attribution
trailers (skipped only on `dev` → `main`, ADR-0060) and `make docs-check`.

`.github/workflows/release-gate.yml` runs on every PR to `main` and on
`workflow_dispatch` (ADR-0052). Its `branch` job runs
`scripts/branch_check.sh <head> main`, so only `dev` or `release/x.y.z` pass
(guidelines 03); every other job needs it. A dispatch checks its `head`
input (default `dev`) instead of a PR head, so the gate can be run on any
ref: `gh workflow run release-gate.yml --ref <branch> [-f head=<name>]`.
The workflow must exist on `dev` for a dispatch to find it. Dispatching is
for the owner; the loop never does it (ADR-0076). The `desktop`
job runs `:desktop:app:test`, `:desktop:proxy:test`, `:desktop:server:test`
and `:protocol:jvmTest`, one step each, on ubuntu, macOS and windows
(`fail-fast: false`, reports uploaded on failure). On macOS it sets
`APPMONITOR_NO_LOCAL_NETWORK=1`, which drops the mDNS round trip test: the
hosted runner has no local network access (DEBT-0046). The `contract`
job, on macOS, installs XcodeGen with Homebrew, runs `make swift` (proto
freshness, swift-format, `swift test` with the fixture tests, public API),
`make swift-sim` and `./gradlew :protocol:allTests`
(`iosSimulatorArm64Test` included); the simulator log and the protocol
reports are uploaded on failure. The `samples` job, on macOS, installs
XcodeGen and runs `make android-samples kmp-sample` (the Android sample's
`build`, the Gradle plugin's TestKit tests, the KMP sample in debug and
release with its SDK name scans); `samples-ios`, beside it, runs `make
ios-sample` (the iOS sample in debug and release, without gRPC-Core, ADR-0163);
their logs are uploaded on failure (ADR-0076, ADR-0154). The `e2e-android` job, on ubuntu,
enables KVM, builds the debug APK and the `installDist` of the server and
of `tools/e2e-target`, then
boots an API 36 `google_apis` x86_64 emulator with
`reactivecircus/android-emulator-runner` and runs `make e2e-android` in it;
`build/e2e-android/` (server log, logcat) is uploaded on failure.
The `e2e-ios` job, on macOS, installs XcodeGen and runs `make e2e-ios`,
which boots the runner's first available iPhone simulator; the sample
reaches the server on `127.0.0.1`, so the runner's missing local network
access (DEBT-0046) does not apply. `build/e2e-ios/` (server, simulator
and build logs) is uploaded on failure. The `e2e-ios-grpc` job is the
same with `make e2e-ios-grpc`, the only gate job that builds gRPC-Core,
timeout 50 min, `build/e2e-ios-grpc/` uploaded on failure (ADR-0163).
The `e2e-kmp-android` job, on ubuntu, is `e2e-android`'s with the KMP
sample's debug APK and `make e2e-kmp-android`; the `e2e-kmp-ios` job, on
macOS, is `e2e-ios`'s with `make e2e-kmp-ios` (ADR-0065 C.1). Their
`build/e2e-kmp-*` directories are uploaded on failure.
The `package` job builds the unsigned `.dmg` on macOS, `.msi` on windows
and `.deb` on ubuntu, one matrix entry each, and uploads each as the
`desktop-<format>` artifact for 7 days.
The `publish` job, on macOS, runs `make publish-dry-run` and uploads
`build/maven-dry-run/` as the `maven-dry-run` artifact for 7 days.

`main` requires the gate through the `protect-main` repository ruleset
(ADR-0053): no deletion, no force-push, merge through a PR only, and every
gate job plus `pr-conventions.yml`'s `conventions` and `docs` as required
checks, matrix entries by their full check name. The ruleset lives in
`.github/rulesets/protect-main.json`; after editing it, apply it with
`gh api -X PUT repos/christianalexandre/app-monitor/rulesets/25604903 --input .github/rulesets/protect-main.json`
and read what GitHub enforces with
`gh api repos/christianalexandre/app-monitor/rules/branches/main`. A gate
job renamed, or a matrix changed, must change the file in the same PR
(DEBT-0048). When `branch` fails, the other jobs are skipped and a skipped
matrix job reports under its bare name (`desktop`), not a required one; the
failed `branch` check is what blocks the merge (seen on the probe PR #130).

## Limits

iOS targets build only on macOS. On Linux/Windows the desktop app and the
Android/JVM parts build normally. The desktop UI test renders offscreen and
needs no display.
