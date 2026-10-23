# 03 — Code map

Where each part lives in the repository.

## Today

| Path | What |
| --- | --- |
| `AGENTS.md` | Agent instructions (`CLAUDE.md` links here) |
| `docs/` | SDD, guidelines, ADRs, tech debt, plans; `screenshots/` for UI evidence |
| `.claude/` | Shared Claude Code settings and slash commands |
| `.github/` | Issue forms, one PR template per change type; workflows `pr-conventions.yml` (branch, title, trailers, docs) and `ci.yml` (`make check` on ubuntu, ADR-0012) |
| `scripts/` | Shell scripts behind `make` targets; `pr_title_check.sh` used by `pr-conventions.yml`; `loop.sh` also backs `make loop-worktree` (`--worktree`, honours `LOOP_WORKTREE`); a failed fetch or `git worktree add` fails both `make loop-worktree` and `make loop` before any iteration; notifications pass the message to `osascript` as an argument, never inside the AppleScript source; its watchdog polls a `.done` marker (ADR-0014) and a timeout is reported only when its `TERM` ended the session (ADR-0015); a session without a verdict waits for a usage limit only on a non-`allowed` `rate_limit_event`, never on the report's text; the verdict is the last non-blank line of the report, only if it is exactly `LOOP: CONTINUE` or `LOOP: STOP [reason]`, so `LOOP: CONTINUE` followed by text, on the same line or after it, is no verdict; it notifies when the worktree's `status: Active` plan changes between iterations (ADR-0019); `loop_watch.sh` follows the newest `.loop/*.jsonl` and switches when a newer session starts (`LOOP_WATCH_POLL`, default 2 s); `test_loop.sh` tests `loop.sh` and `loop_watch.sh` (ADR-0013) |
| `Makefile` | Command façade |
| `settings.gradle.kts`, `build.gradle.kts` | Gradle root: module list, root lint and `check` |
| `gradle/libs.versions.toml` | Version catalog, shared with `build-logic` |
| `gradle/wrapper/`, `gradlew*` | Gradle wrapper (pinned, checksummed) |
| `build-logic/` | Convention plugins `appmonitor.*` (ADR-0006) |
| `protocol/proto/` | Protobuf schema `appmonitor.v1` (ADR-0008) |
| `protocol/fixtures/` | Golden encoded envelopes, one per message |
| `protocol/src/` | KMP module: Wire-generated code, `Protocol` constants, `DesktopSelector` (mDNS answer rule, ADR-0024); `commonTest` round-trip and selector, `jvmTest` fixture check (ADR-0008) |
| `desktop/server/` | JVM module: `AppMonitorServer` (Ktor WebSocket, handshake, frame limit, keep-alive), `EnvelopeRules` (what closes a session with 1002, ADR-0021), `DeviceSession` flows, `DeviceApproval` / `DeviceApprovals` (who gets `HelloAck`, ADR-0027), `MdnsAdvertiser`, `ServerConfig` (ADR-0009); `shutdownHook` and its default report `printStopFailure` (bounded stop at exit, shared with the app); headless `Main.kt` that logs to stdout (ADR-0010); `AppMonitorServerTest`, `ServerHardeningTest` (1009, ping timeout, envelope rules), `DeviceApprovalTest` (pending, 1008, withdrawal), `MdnsPartialFailureTest`, `ServerLifecycleTest`, `ShutdownHookTest`, `MainTest` (`--port` parsing, startup hook) |
| `desktop/app/` | Compose desktop app: `Main.kt` (window + server), `store/` (`TrafficStore`, `TrafficState`), `ui/` (`App.kt` panes, frames and approval panel, `BodyFormat.kt` formats, off-thread `renderBody` and chunks) (ADR-0011, ADR-0029); tests for state, body formats and the offscreen UI |
| `tools/fake-client/` | JVM CLI: `Discovery.kt` (mDNS through `DesktopSelector`), `Fixtures.kt`, `Replay.kt` (handshake + replay of `protocol/fixtures`) (ADR-0010); `FakeClientTest`, `HandshakeFailureTest`, `MainTest` (CLI errors), `DiscoveryTest` |
| `sdk/core/` | KMP (JVM + Android) SDK core, convention `appmonitor.kmp-sdk` (ADR-0023): `MonitorClient` (states of SDD 04 over a Ktor CIO WebSocket), `EventBuffer` (offline ring buffer), `Backoff`, `Discovery` (sources and mDNS selection, ADR-0024), `MonitorHolder` (start/stop, the process-wide `monitor`), `MonitorBridge` (opt-in `InternalAppMonitorApi` for capture modules, ADR-0025), `AppMonitorOptions` (masking and `maxBodyBytes` included), `DeviceId`, `Platform` (cleartext policy, log); `androidMain` `AppMonitor` (public entry point), `NsdBrowser`, `AndroidDevice` (`Hello`, permission, emulator), manifest declaring `ACCESS_LOCAL_NETWORK`; `commonTest` buffer, backoff, discovery, device id; `jvmTest` `MonitorClientTest` against an in-process `AppMonitorServer`, `MonitorHolderTest` |
| `sdk/android-okhttp/` | KMP (JVM + Android) OkHttp capture, convention `appmonitor.kmp-sdk` (SDD 05, ADR-0025): `AppMonitorInterceptor`, `AppMonitorWebSocketFactory`, `Capture.kt` (sink, masking, bounded request body, event builders); `jvmTest` against `mockwebserver3` |
| `sdk/no-op/` | KMP (JVM + Android) release artifact (ADR-0026): the public types of `sdk/core` and `sdk/android-okhttp`, same names, doing nothing; depends on OkHttp only; `jvmTest` `ApiMatchTest` (dumps per target), `OptionsDefaultsTest` |
| `samples/android/` | Compose sample app, convention `appmonitor.android-app` (ADR-0030): `SampleApplication` (starts the monitor), `MainActivity` (buttons), `SampleCalls` (OkHttp calls to `httpbin.org` and `echo.websocket.org`); `src/debug` depends on `sdk/android-okhttp`, asks for `ACCESS_LOCAL_NETWORK` on Android 17+, allows cleartext; `src/release` depends on `sdk/no-op`; `checkReleaseHasNoSdk` in `check` |
| `sdk/*/api/` | Committed ABI dumps per target (`android`, `jvm`) of every SDK module, checked by `checkKotlinAbi` in `check`; `InternalAppMonitorApi` excluded (ADR-0026) |
| `.loop/` | `make loop` session logs (git-ignored): `.jsonl` stream, `.jsonl.err` stderr, `.md` report; a transient `.jsonl.done` while a session ends |

## Planned layout

```
sdk/ktor/            Ktor HttpClient plugin (KMP)
sdk/ios/             Swift package
desktop/storage/     SQLDelight
desktop/mcp/         MCP server
desktop/feature-*/   one module per debug tool (channel + UI)
samples/             iOS and KMP sample apps
```

This table moves to "Today" as modules land.
