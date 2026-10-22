# 03 — Code map

Where each part lives in the repository.

## Today

| Path | What |
| --- | --- |
| `AGENTS.md` | Agent instructions (`CLAUDE.md` links here) |
| `docs/` | SDD, guidelines, ADRs, tech debt, plan |
| `.claude/` | Shared Claude Code settings and slash commands |
| `.github/` | PR/issue templates and workflows |
| `scripts/` | Shell scripts behind `make` targets |
| `Makefile` | Command façade |

## Planned layout

```
protocol/            .proto files + generation
sdk/core/            KMP: connection, discovery, buffer, reconnection
sdk/android-okhttp/  OkHttp interceptor
sdk/ktor/            Ktor HttpClient plugin (KMP)
sdk/no-op/           empty artifacts for release builds
sdk/ios/             Swift package
desktop/server/      Ktor server + mDNS
desktop/storage/     SQLDelight
desktop/mcp/         MCP server
desktop/feature-*/   one module per debug tool (channel + UI)
desktop/app/         Compose app, DI, navigation
samples/             Android, iOS and KMP sample apps
```

This table moves to "Today" as modules land.
