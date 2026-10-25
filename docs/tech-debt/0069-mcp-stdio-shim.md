---
id: DEBT-0069
title: No stdio shim of our own for the MCP server
status: Open
date: 2026-10-24
trigger: The first MCP client the owner uses that speaks neither Streamable HTTP nor runs `mcp-remote`
paid_date:
---

# DEBT-0069 — No stdio shim of our own for the MCP server

## What is missing

The desktop serves MCP over Streamable HTTP only (ADR-0091). A client that
only launches stdio servers has to go through `mcp-remote`; the project
ships no forwarder of its own.

## Why it was accepted

Claude Code and the MCP Inspector speak HTTP, and `mcp-remote` (MIT) covers
stdio-only clients. A shim would be one more artifact to build, test and
distribute in M10, a JVM start per client launch and a token to hand it.

## Trigger

The first MCP client the owner uses that speaks neither Streamable HTTP
nor runs `mcp-remote`.

## How it was paid

> Filled when closing.
