---
id: ADR-0089
title: What the MCP tools return, and how bodies are capped
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0089 — What the MCP tools return, and how bodies are capped

## Context

PLAN-0007 M6.1 (a). The desktop's MCP server (ADR-0005) hands stored
traffic to an AI agent. What it exposes is a "stop and ask" area
(`AGENTS.md`, security / privacy): ADR-0081 left it to M6.

Already decided, not reopened here:

- The store keeps everything the desktop receives, as received: headers,
  URLs, bodies and frame payloads byte for byte (ADR-0081 A.1).
- Masking runs on the device: the default rules mask `Authorization`,
  `Proxy-Authorization`, `Cookie`, `Set-Cookie`, URL user info and URLs in
  response headers; bodies are never masked (ADR-0018 B, ADR-0032,
  ADR-0033, DEBT-0019). `masking = false` turns every rule off (ADR-0034).
  The desktop cannot tell masked traffic from unmasked.
- A body or frame payload is at most `maxBodyBytes`, 1 MiB by default, cut
  on the device (ADR-0018 C, ADR-0020, ADR-0028).
- The tools are read-only (ADR-0005). Their list is PLAN-0007's goal.

Evidence on what an agent can take in:

- Claude Code "displays a warning when MCP tool output exceeds 10,000
  tokens and limits output to 25,000 tokens by default"
  (`MAX_MCP_OUTPUT_TOKENS` changes the limit); "successful text results
  longer than 50,000 characters are saved to a file"
  ([Claude Code, MCP](https://code.claude.com/docs/en/mcp), read
  2026-10-24). A full 1 MiB body is far past both.
- The window already decides text from binary: JSON when the
  `Content-Type` says so and it parses, text when it is UTF-8, else hex
  (SDD 01, desktop app).

## Options

### A. What a tool returns

1. **What is stored, as stored** — headers, URLs, bodies and frames exactly
   as the device sent them; with `masking = false` that includes tokens and
   cookies. What the agent sees is what the window shows. Every tool's
   description says the data may hold secrets when the app turned masking
   off.
2. **Desktop-side masking on the way out** — the MCP server re-masks the
   default headers and URL parts even when the device sent them in clear;
   contradicts the developer's explicit `masking = false` (ADR-0034) and
   adds a second place where masking happens (rejected for storage in
   ADR-0081 A.3).
3. **Metadata only** — method, URL, status, timings, sizes, never headers
   or bodies; safest, but an agent cannot answer "why did this request
   fail" from a status code alone.

Recommended: 1.

### B. Body size in a tool result

1. **A cap per body, with paging** — lists (`search_requests`,
   `list_runs`, stats) never carry bodies or headers, only the row fields.
   `get_request` returns each body and each frame payload up to
   **16 KiB** by default (about 4k tokens, under the 10k warning even with
   both bodies), with its stored size and a `truncated` flag that says
   whether the device cut it (ADR-0020) and whether the tool did. Optional
   `body_offset` and `body_max_bytes` (up to 256 KiB) read the rest.
   Frames come by page, like the window.
2. **Whole bodies** — simplest; a single 1 MiB response body overflows
   Claude Code's default limit and is cut or saved to a file there,
   outside the server's control.
3. **Bodies only through a separate tool** — the same cap problem, one more
   tool than PLAN-0007 lists (product scope).

Recommended: 1.

### C. Binary bodies

1. **UTF-8 as text, anything else as base64** — the server applies the
   window's test (valid UTF-8 or not) to the capped slice; non-UTF-8 bytes
   come as base64 with `encoding: "base64"`, the `Content-Type` and the
   size. Base64 is 4/3 of the bytes, hex is 2.
2. **Hex, like the window** — what the developer sees; half the bytes per
   token of base64.
3. **Size and type only** — an image or protobuf body is rarely useful to
   an agent; loses gRPC and protobuf payloads M8 will capture.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- A.1 makes every MCP client as trusted as the window: who may call the
  server is ADR-0090.
- B.1 and C.1 are the shapes M6.2 writes into each tool's schema and M6.4
  tests ("a body past the cap").
- `export_har` (M6.7) is a file for the developer, not an agent's
  context: whether it carries whole bodies is M6.2's, within A.
