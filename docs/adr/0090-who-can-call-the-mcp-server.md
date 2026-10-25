---
id: ADR-0090
title: Who can call the MCP server, on which port, and whether it is on by default
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0090 — Who can call the MCP server, on which port, and whether it is on by default

## Context

PLAN-0007 M6.1 (b). Whoever reaches the MCP server reads the stored
traffic, which may hold tokens and bodies in clear (ADR-0089 A, ADR-0034).
Who may call it is a "stop and ask" area (`AGENTS.md`, security /
privacy).

Already decided, not reopened here:

- The server runs inside the desktop app over Streamable HTTP, bound to
  `127.0.0.1` only (ADR-0005).
- The data directory is `0700`, its files `0600` on macOS and Linux, and
  `%LOCALAPPDATA%` is per-user on Windows (ADR-0081 C.1): another OS user
  cannot read the database file.
- The device server listens on every interface on `47474` (SDD 01,
  `Protocol.DEFAULT_PORT`), so the MCP endpoint cannot share its listener.

Evidence:

- The MCP transport spec, security warning for Streamable HTTP:
  "Servers **MUST** validate the `Origin` header on all incoming
  connections to prevent DNS rebinding attacks"; "When running locally,
  servers **SHOULD** bind only to localhost (127.0.0.1)"; "Servers
  **SHOULD** implement proper authentication for all connections"
  ([MCP spec 2025-06-18, Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)).
- Any process on the machine, of any OS user, can open a TCP connection to
  `127.0.0.1`: the loopback bind keeps the LAN out, not other local users.
- Claude Code passes a header to an HTTP server with
  `claude mcp add --transport http <name> <url> --header "Authorization:
  Bearer …"`, and expands `${VAR}` in the `url` and `headers` of a
  `.mcp.json` ([Claude Code, MCP](https://code.claude.com/docs/en/mcp),
  read 2026-10-24).
- Neither 47474 nor 47475 is in the IANA service name and port registry
  (`service-names-port-numbers.csv`, downloaded 2026-10-24; the nearest
  entry is 47557).

## Options

### A. `Host` and `Origin` checks

1. **Loopback names only** — `Host` must be `127.0.0.1:<port>` or
   `localhost:<port>`; a request with an `Origin` is accepted only when it
   is `http://127.0.0.1:<port>` or `http://localhost:<port>`; anything else
   gets `403` before it reaches the MCP SDK. A request with no `Origin`
   (CLI clients send none) passes. A DNS-rebinding page carries its own
   host name in both headers and is refused.
2. **A configurable allow-list** — the same, plus origins the developer
   adds; no client needs one yet.

Recommended: 1.

### B. A token

1. **No token** — loopback and the `Host`/`Origin` checks only; any local
   process of any OS user reads the traffic the `0600` file keeps from
   them.
2. **A bearer token** — 32 random bytes from `SecureRandom`, base64url,
   generated on first launch and kept in the store's `settings` table
   (inside the `0600` file); every request without
   `Authorization: Bearer <token>` gets `401`. The settings show the
   ready-to-paste `claude mcp add` command with the token in it, so the
   setup stays one copy-paste; "Regenerate" invalidates the old one.
   Closes the gap between the file's permissions and the socket's.
3. **OAuth 2.1, the spec's authorization flow** — a full authorization
   server inside a debug tool, for one local user.

Recommended: 2.

### C. On by default

1. **On when the app starts, with a switch in the settings to turn it
   off** — no setup beyond pasting the command; with B.2 an unconfigured
   process still gets `401`. The switch is persisted in `settings`.
2. **Off until switched on in the settings** — one more step before the
   first query; nothing listens until the developer asks.

Recommended: 1.

### D. Port and path

1. **Fixed `47475`, path `/mcp`** — next to the device port, stable across
   launches so the client's config keeps working; a port already taken
   shows a status in the settings, not a crash (M6.8). An
   `APPMONITOR_MCP_PORT` environment variable moves it, like
   `APPMONITOR_DATA_DIR` (ADR-0081 B.1), for tests and two desktops side
   by side.
2. **An ephemeral port** — never taken, but the client's config breaks on
   every launch.
3. **A port picked in the settings** — one more field; the variable in D.1
   covers the same need until someone asks.

Recommended: 1.

## Decision

The owner accepted every recommendation: A.1 (`Host` and `Origin` must be
loopback names), B.2 (a bearer token, shown in a ready-to-paste
`claude mcp add` command in the settings), C.1 (on when the app starts,
with a switch to turn it off) and D.1 (fixed port `47475`, path `/mcp`,
`APPMONITOR_MCP_PORT` overrides it).

## Consequences

- B.2 adds a secret to the `settings` table; "Clear all data" (ADR-0088)
  clears settings, so it regenerates the token and the developer pastes
  the command again.
- A.1, B.2 and D.1 are cells of M6.2's lifecycle table and cases of
  M6.3's tests (foreign `Origin`, missing token, port taken).
- Malware running as the same OS user is out of scope: it can read the
  database file directly.
