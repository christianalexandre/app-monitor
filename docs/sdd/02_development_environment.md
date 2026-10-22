# 02 — Development environment

What a machine needs to build and test the whole repository.

## Toolchain

| Tool | Version | Used by |
| --- | --- | --- |
| JDK | 21 | Gradle, desktop app |
| Xcode | 26.x | iOS SDK, iOS samples, simulator tests (macOS only) |
| Android SDK | `ANDROID_HOME` set, `platform-tools` and `emulator` on `PATH` | Android SDK, emulator tests |
| GitHub CLI | authenticated | PRs from `/pr` and `/next` |
| GNU Make | any | command façade |

No `protoc` is needed: Wire generates Kotlin without it (ADR-0001).

## Running things

Nothing to run yet. The commands table in `AGENTS.md` is the source of truth
and grows with each module.

## Limits

iOS targets build only on macOS. On Linux/Windows the desktop app and the
Android/JVM parts build normally.
