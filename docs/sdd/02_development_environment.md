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
| `appmonitor.kmp-library` | shared KMP code (protocol, SDK core) | targets `jvm`, `android` (host tests on), `iosArm64`, `iosSimulatorArm64`; module sets `namespace` (ADR-0007) |
| `appmonitor.compose-desktop` | desktop UI modules | `kotlin-jvm` + Compose compiler + Compose Multiplatform |

## Limits

iOS targets build only on macOS. On Linux/Windows the desktop app and the
Android/JVM parts build normally.
