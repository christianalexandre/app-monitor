---
id: ADR-0074
title: The end to ends call a local Ktor target passed to the samples at launch
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0074 — The end to ends call a local Ktor target passed to the samples at launch

## Context

DEBT-0047 fired on 2026-10-24: release gate run 40093851446 failed
`e2e-android` with `no ResponseReceived` for every `httpbin.org` and
`echo.websocket.org` call, and the rerun passed with no code change.
PLAN-0005 M4.8a asks `make e2e-android` and `make e2e-ios` (ADR-0056,
ADR-0057) to call a target on the host instead, with the public URLs kept as
the samples' default for hand runs; `make e2e-kmp` (M4.10) reuses it.

Already decided and not reopened: the samples read launch extras on Android
and launch arguments on iOS (ADR-0056, ADR-0057); the debug Android samples
already permit cleartext (`network_security_config.xml`, ADR-0017 F).

Evidence, from runs on 2026-10-24 on an Apple Silicon Mac: the iOS sample
on an iPhone 17 Pro simulator (iOS 26.5) loads `http://127.0.0.1:N`, the
background download included, without an App Transport Security key;
the emulator `Medium_Phone` reaches a server bound to the host's
`127.0.0.1` as `10.0.2.2`; both runs passed with every `RequestStarted` URL
on the target.

## Options

1. **Python's `http.server`** — no build, but no WebSocket: the echo would be
   a hand-written RFC 6455 handshake and framing, and the gate's runners
   would need a Python on the `PATH`.
2. **A route set inside `desktop/server`** — no new module, but the headless
   server would serve traffic unrelated to the protocol, and `make server`
   with it.
3. **A JVM module `tools/e2e-target`** on Ktor server CIO and WebSockets,
   the libraries `desktop/server` already uses: the httpbin.org paths the
   samples call (`/json`, `/post`, `/put`, `/status/{code}`,
   `/delay/{s}`, `/bytes/{n}`) and an echo on `/ws`, bound to `127.0.0.1`
   on port 0, `Listening on port N` as the headless server prints it. The
   samples take its base URL as `target` and derive the WebSocket URL from
   it.

## Decision

Option 3. The scripts start it beside the headless server through
`scripts/e2e_lib.sh` and pass `--es target http://10.0.2.2:N` on Android,
`-target http://127.0.0.1:N` on iOS; the samples call the public services
when `target` is absent. `assert_envelopes` also fails when a
`RequestStarted` URL is not on the target's authority.

## Consequences

- The end to ends need no internet once built; status codes, TLS and the
  public services' greeting frame are no longer exercised by them.
- A sample call to a new path needs that path in `tools/e2e-target`; a call
  the target does not serve answers 404 and still passes, since status
  codes are not asserted (ADR-0056).
- The target listens on loopback only, so nothing on the LAN reaches it.
