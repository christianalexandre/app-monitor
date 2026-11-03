---
id: ADR-0176
title: The headless server is a tool module
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0176 — The headless server is a tool module

## Context

`desktop/server` carried `make server`'s `main` and its flags (ADR-0010,
ADR-0109, ADR-0116, ADR-0122), so it depended on `desktop/proxy` only for
`--proxy`. Everything using the device server (`desktop/app`, `tools/fake-client`
and `sdk/core`'s tests) took Netty and BouncyCastle with it, and `desktop/proxy`
could never depend on it (DEBT-0105).

## Options

1. **Keep `Main.kt`, make the proxy optional at runtime.** Reflection for a test flag.
2. **Move `Main.kt`, `MockFile.kt` and `ReplayAsk.kt` to `tools/headless-server`,**
   beside `tools/e2e-target` and `tools/fake-client`, the other programs the
   end to end scripts start.

## Decision

Option 2. `desktop/server` keeps the device server and `shutdownHook`, with
`startWithShutdownHook` beside it, public, tested there since it needs the
server's test constructor. `make server` keeps its name; the launcher is
`tools/headless-server/build/install/headless-server/bin/headless-server`.

## Consequences

- `desktop/server` no longer has `application`, `desktop/proxy` or JSON.
- The end to end scripts and the release gate build `:tools:headless-server:installDist`.
  The gate's desktop job runs its tests beside `:desktop:server:test`, so its
  three operating systems keep covering what moved.
