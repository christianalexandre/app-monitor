---
id: ADR-0184
title: The monitor's connect has one timeout, its own, not CIO's
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0184 — The monitor's connect has one timeout, its own, not CIO's

## Context

ADR-0067 C stopped `stop()` from cancelling a connect at once, since a
cancelled connect could lose its open socket on iOS, and left the 1 s
release bound cancelling one still in its TCP phase (DEBT-0053). The plan
asks for that socket to be closed (PLAN-0016 H6.3).

Evidence, on the iOS simulator with Ktor 3.6.0:

- `ktor-client-cio` `Endpoint.kt`, `connect`: with a `connectTimeout` it runs
  the connect inside `withTimeoutOrNull`, a coroutine of its own. When the
  caller is cancelled as that coroutine returns, its result, the open socket,
  never reaches the caller. With `HttpTimeoutConfig.INFINITE_TIMEOUT_MS` it
  calls the connect directly.
- `ktor-network` `ConnectUtilsNative.kt`, `tcpConnect`: closes the socket on any
  throwable from `socket.connect`, a cancellation included.
- `aConnectCancelledAtAnyMomentClosesItsSocket`, 2,000 stops swept across the
  connect: with CIO's default timeout, 15 of 1,762 and 11 of 1,780 accepted
  sockets stayed open. With the infinite one, 0 of 1,761, 1,736, 1,776, 1,766
  and 1,753, five runs of five.
- `MonitorClient`'s own watchdog already bounds the upgrade by
  `ClientConfig.connectTimeout`, 10 s, the bound SDD 04 states. CIO's default,
  5 s, cut in first on a host that drops SYNs.

## Options

1. **CIO without a connect timeout, the watchdog the only bound** — one setting,
   on every target. The connect is cancelled where Ktor closes the socket.
2. **Never cancel a connect: leave it running past `stop()` on its own scope** —
   a stopped client keeps a coroutine and the engine alive for seconds, and the
   watchdog's and CIO's own cancellations stay as they were.
3. **Wait for a Ktor fix** — no release has it (3.6.1 is the latest) and no
   issue is filed from the loop, an outward action.

## Decision

Option 1. `MonitorClient` sets `endpoint.connectTimeout` to
`HttpTimeoutConfig.INFINITE_TIMEOUT_MS`.

## Consequences

- A cancelled connect closes its socket on iOS; DEBT-0053 is paid.
- An unreachable host that drops SYNs now fails at 10 s, not 5 s, as SDD 04
  always said, and backs off from there.
- A Ktor change to `connect` could bring the leak back; the test runs on the
  simulator in `:sdk:core:allTests`, about 3 s.
