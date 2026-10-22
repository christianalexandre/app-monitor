---
id: ADR-0005
title: SQLite storage and in-process MCP server
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0005 — SQLite storage and in-process MCP server

## Context

The desktop must keep history (runs), let the developer save issues, compute
benchmarks across runs, and let AI agents query all of it, live.

## Options

1. **In-memory only** — simplest; no history, no benchmarks.
2. **SQLite via SQLDelight + MCP as a separate process** — the MCP process
   would read the database file; no access to live, unsaved state.
3. **SQLite via SQLDelight + MCP inside the desktop process** — one source of
   truth, live data, single install.

## Decision

SQLite through SQLDelight (typed queries, migrations verified at build time).
Bodies are stored compressed in a separate table so list queries stay fast.
The MCP server runs inside the desktop app using the official Kotlin MCP SDK,
over Streamable HTTP bound to `127.0.0.1` only. Tools are read-only at first;
tools that change app behavior (mocks, replay) require confirmation in the UI.

## Consequences

- The desktop must be running for agents to query it.
- Schema migrations are part of every storage change.
- A stdio shim may be needed for clients without HTTP MCP support.
