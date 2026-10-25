---
id: ADR-0091
title: Which MCP clients the desktop serves, and what the setup docs cover
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0091 — Which MCP clients the desktop serves, and what the setup docs cover

## Context

PLAN-0007 M6.1 (c). ADR-0005 chose Streamable HTTP and noted that "a stdio
shim may be needed for clients without HTTP MCP support". Which clients the
project supports and documents is product scope (`AGENTS.md`).

Already decided, not reopened here:

- The server is in the desktop process, Streamable HTTP, `127.0.0.1` only
  (ADR-0005); its access rules are ADR-0090.

Evidence:

- The MCP spec defines two standard transports, stdio and Streamable HTTP,
  and says "Clients **SHOULD** support stdio whenever possible"; Streamable
  HTTP replaces the 2024-11-05 HTTP+SSE transport
  ([MCP spec 2025-06-18, Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)).
- Claude Code adds an HTTP server with `claude mcp add --transport http`,
  and says "The SSE (Server-Sent Events) transport is deprecated. Use HTTP
  servers instead" ([Claude Code, MCP](https://code.claude.com/docs/en/mcp),
  read 2026-10-24).
- The Kotlin MCP SDK has server transports for stdio, SSE ("for backwards
  compatibility … Prefer Streamable HTTP for new projects"), Streamable
  HTTP (`mcpStreamableHttp()` on Ktor) and WebSocket
  ([kotlin-sdk README](https://github.com/modelcontextprotocol/kotlin-sdk),
  read 2026-10-24); versions and licence are M6.2's ADR.
- `mcp-remote` (MIT, `gh api repos/geelen/mcp-remote`) bridges a
  stdio-only client to a remote HTTP server and passes headers with
  `--header "Authorization: Bearer ${AUTH_TOKEN}"` (its README).

## Options

1. **Streamable HTTP only; docs for Claude Code and any HTTP client** —
   the setup page covers Claude Code (the `claude mcp add` command the
   settings show, and the same server in a project's `.mcp.json` with the
   token from a `${VAR}`), a generic "URL plus `Authorization` header"
   section for other Streamable HTTP clients, and the MCP Inspector to
   check the server by hand. A stdio-only client is pointed at
   `mcp-remote` in one line, not supported by the project. Nothing new to
   build or ship.
2. **Streamable HTTP plus SSE** — the SDK has it; Claude Code calls it
   deprecated and falls back to it on its own only for servers that lack
   HTTP. One more endpoint to secure under ADR-0090.
3. **Streamable HTTP plus a stdio shim of our own** — a small JVM program
   that the client launches and that forwards stdin/stdout to the HTTP
   endpoint; reaches every stdio-only client, but is one more artifact to
   build, test and distribute (M10), a JVM start per client launch, and a
   token to hand it.

Recommended: 1. A shim of our own follows (DEBT) if a client the owner
uses cannot speak HTTP or run `mcp-remote`.

## Decision

The owner accepted the recommendation: option 1, Streamable HTTP only,
with setup docs for Claude Code, any HTTP client and the MCP Inspector,
and a pointer to `mcp-remote` for stdio-only clients. A shim of our own is
DEBT-0069.

## Consequences

- M6.9's setup docs and live check follow option 1: Claude Code with the
  documented command, the MCP Inspector as the second client.
- `make server` (headless) serving MCP is M6.2's choice; nothing here
  depends on it.
