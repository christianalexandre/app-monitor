---
id: ADR-0010
title: Fake client replays golden fixtures; desktop server runs headless
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0010 — Fake client replays golden fixtures; desktop server runs headless

## Context

PLAN-0001 M0.4 asks for `tools/fake-client`, a JVM CLI that discovers the
desktop and replays fixture traffic, proven by "the requests show in the
server log". `desktop/server` (ADR-0009) is a library with no entry point and
no log, and the desktop app (M0.5) does not exist yet. The release gate (M3)
will also need a desktop server without UI for device e2e tests.

Evidence, checked on 2026-10-21 on this branch:

- `protocol/fixtures/*.bin` are the encoded envelopes pinned by
  `FixtureTest` (ADR-0008); they decode with the Wire runtime alone.
- Two JmDNS instances on one host sharing a host name conflict; the M0.3
  mDNS test needed a distinct name for its browser
  (`AppMonitorServerTest`). The fake client, browsing on the machine that
  advertises, hits the same case.
- Ktor's CIO server logs through SLF4J; with no provider on the classpath it
  prints a three-line `No SLF4J providers were found` warning and nothing
  else (observed running `make server`).

## Options

1. **Traffic source: fixture files** — replay `protocol/fixtures` as-is,
   copied into the fake client's resources at build time. One source of truth
   with the contract tests; content is fixed (one request of each kind).
2. **Traffic source: generated** — build envelopes in code (random URLs,
   N requests). More realistic volume, but a second definition of "valid
   traffic" that can drift from the fixtures.
3. **Server log: logging framework** (SLF4J + Logback) vs **stdout
   lines** from a `main` in `desktop/server`. A framework is configurable
   but adds a dependency and config for a tool whose only reader is a person
   or a test watching stdout.

## Decision

Option 1: the fake client bundles `protocol/fixtures` as classpath resources
(`processResources`), sends `hello.bin`, waits for `HelloAck`, then sends
the four `network` fixtures with `timestamp_ms` set to the send time.
Discovery browses `_appmonitor._tcp` with one JmDNS per LAN IPv4 address
under its own host name, `appmonitor-fake-client`; `--host` skips it.
`desktop/server` gains a headless `main` (Gradle `application` plugin) that
prints one stdout line per session event and envelope; no logging framework.
Arguments are parsed by hand: four flags do not justify a CLI library.

## Consequences

- `make server` + `make fake-traffic` exercise the real protocol end to end
  without the desktop UI; M3 can reuse the headless server.
- Fixture changes reach the fake client on the next build, nothing to sync.
- The traffic is always the same four events; volume or variety for UI work
  needs generated traffic later, decided when a task asks for it.
- The SLF4J warning stays on stdout until the desktop picks a logging setup.
