---
id: ADR-0034
title: A switch that turns every masking rule off
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0034 — A switch that turns every masking rule off

## Context

The SDK masks secrets on the device by default (ADR-0018 A and B), and the
masking rules keep growing: URL user info (ADR-0032), URLs in response
headers and form-encoded fragments (ADR-0033). The SDK is a debug tool: it
ships in debug builds only (`sdk/no-op` in release, ADR-0017) and is used by
the app's own developers. A developer chasing a bug in an auth flow often
needs to see the exact token or redirect that was sent, and today has no
way to, short of not using the SDK. The owner asked for a way to see 100% of
the traffic. What the SDK masks is a "stop and ask" area (`AGENTS.md`);
the owner decided it in this conversation.

Already decided, not reopened here:

- Masking runs on the device, before the wire (ADR-0018 A).
- The rules themselves (ADR-0018 B, ADR-0032, ADR-0033).
- Options live in the immutable `AppMonitorOptions` (ADR-0017 B).

## Options

1. **One flag, `masking: Boolean = true`** — `false` skips every rule; the
   default keeps today's behaviour, so the traffic stays masked over plain
   `ws://` (ADR-0018) and in the desktop's SQLite and MCP unless the
   developer turns it off on purpose. One member, mirrored by `sdk/no-op`
   and by the iOS SDK (M2).
2. **Masking off by default, opt in** — the developer sees everything with
   no setup; secrets then reach every desktop on the LAN (DEBT-0020) and the
   MCP clients by default.
3. **Per-rule flags** (headers, URL, fragment…) — finer control, one more
   member per future rule; no request for it yet.

## Decision

Option 1: `AppMonitorOptions` gains `masking: Boolean = true`; `false` sends
every value as OkHttp saw it — headers, URL user info, query parameters,
fragments and URLs in response headers. `maskedHeaders` and
`maskedQueryParameters` are ignored while it is `false`.

## Consequences

- PLAN-0002 M1.R6 adds the member, its test, the `sdk/no-op` mirror and the
  SDD 05 bullet.
- Every masking rule added later must honour the switch; a test with
  `masking = false` covers it.
- The iOS SDK (M2) reproduces the same option.
- Per-rule flags (option 3) can follow under their own ADR if a developer
  asks for them.
