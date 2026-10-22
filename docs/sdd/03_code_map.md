# 03 — Code map

Where each part lives in the repository.

## Today

| Path | What |
| --- | --- |
| `AGENTS.md` | Agent instructions (`CLAUDE.md` links here) |
| `docs/` | SDD, guidelines, ADRs, tech debt, plans; `screenshots/` for UI evidence |
| `.claude/` | Shared Claude Code settings and slash commands |
| `.github/` | Issue forms, one PR template per change type; workflows `pr-conventions.yml` (branch, title, trailers, docs) and `ci.yml` (`make check` on ubuntu, ADR-0012) |
| `scripts/` | Shell scripts behind `make` targets; `pr_title_check.sh` used by `pr-conventions.yml`; `test_loop.sh` tests `loop.sh` and `loop_watch.sh` (ADR-0013) |
| `Makefile` | Command façade |
| `settings.gradle.kts`, `build.gradle.kts` | Gradle root: module list, root lint and `check` |
| `gradle/libs.versions.toml` | Version catalog, shared with `build-logic` |
| `gradle/wrapper/`, `gradlew*` | Gradle wrapper (pinned, checksummed) |
| `build-logic/` | Convention plugins `appmonitor.*` (ADR-0006) |
| `protocol/proto/` | Protobuf schema `appmonitor.v1` (ADR-0008) |
| `protocol/fixtures/` | Golden encoded envelopes, one per message |
| `protocol/src/` | KMP module: Wire-generated code, `Protocol` constants; `commonTest` round-trip, `jvmTest` fixture check (ADR-0008) |
| `desktop/server/` | JVM module: `AppMonitorServer` (Ktor WebSocket, handshake), `DeviceSession` flows, `MdnsAdvertiser`, `ServerConfig` (ADR-0009); headless `Main.kt` that logs to stdout (ADR-0010); `AppMonitorServerTest`, `MdnsPartialFailureTest` |
| `desktop/app/` | Compose desktop app: `Main.kt` (window + server), `store/` (`TrafficStore`, `TrafficState`), `ui/` (`App.kt` panes, `BodyFormat.kt`) (ADR-0011); tests for state, body formats and the offscreen UI |
| `tools/fake-client/` | JVM CLI: `Discovery.kt` (mDNS), `Fixtures.kt`, `Replay.kt` (handshake + replay of `protocol/fixtures`) (ADR-0010); `FakeClientTest`, `HandshakeFailureTest` |
| `.loop/` | `make loop` session logs (git-ignored) |

## Planned layout

```
sdk/core/            KMP: connection, discovery, buffer, reconnection
sdk/android-okhttp/  OkHttp interceptor
sdk/ktor/            Ktor HttpClient plugin (KMP)
sdk/no-op/           empty artifacts for release builds
sdk/ios/             Swift package
desktop/storage/     SQLDelight
desktop/mcp/         MCP server
desktop/feature-*/   one module per debug tool (channel + UI)
samples/             Android, iOS and KMP sample apps
```

This table moves to "Today" as modules land.
