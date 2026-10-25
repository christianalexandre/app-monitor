---
id: DEBT-0081
title: Non-correctness findings of the M8 milestone review
status: Open
date: 2026-10-25
trigger: "The next PR that touches `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ClientHandler.kt`, `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/Http2Relay.kt` or `sdk/ios/Sources/AppMonitorConnections/connections.c`"
paid_date:
---

# DEBT-0081 — Non-correctness findings of the M8 milestone review

## What is missing

Findings of the `code-review` of `d5f2e82..5115fdb` that normal use does
not hit (ADR-0094):

- **An early answer leaves the request body behind.** In `ClientHandler.kt`,
  `responseEnded` clears the exchange, and `content()` then drops the rest
  of a request body the origin answered before reading, such as a large
  upload refused with 401 or 413. The upstream connection stays open for
  reuse, so the next request on it can hang or read as the old body.
- **No backpressure in the relays.** `Http2Relay.kt` and
  `ClientHandler.forward` write with `writeAndFlush` and never check
  `isWritable`. A fast client streaming to a slow origin fills the
  proxy's memory.
- **A socket closed outside the rebound `close` keeps its slot.** In
  `connections.c`, `counted[fd]` is cleared only by the rebound `close`.
  If a system image, `dup2` or a CFStream that owns the native socket
  closes a counted fd, the exchange never ends. The next app connect
  that reuses that fd number is then added to the old exchange.
- **A failed proxy start leaves an empty session.** `ProxyController`
  builds the `ProxyRecording`, which creates the "Proxy" session row,
  before `server.start()`. A port already taken therefore leaves one
  empty session per attempt.
- **Paid by H2.5a: connections counted against the HAR cap.** `ExportHar.log` takes
  `max_entries` rows and only then leaves connections out. `log.comment`
  counts the connections, but the file can hold fewer HTTP entries than
  the cap while more matched.
- **Every connection that read bytes is marked truncated.** In
  `ConnectionCapture.swift`, `bodyTruncated = received > 0`. No reader
  shows it today, because `get_request` and the detail drop a
  connection's response (ADR-0104), but a future reader would report the
  body as cut by the device.
- **The end to end can read the gRPC port too early.** `e2e_ios.sh` reads
  `gRPC on port` once `Listening on port` is in the log. The target
  prints the two lines one after the other, so a slow write can leave
  `grpc_port` empty.
- **Two `statusOf` functions.** `App.kt` has one for `HttpExchange` and one
  for `StoredExchange`, each with the connection and `grpc N` rules
  written again.

## Why it was accepted

Each one needs a body larger than what is in flight when the answer
comes, a slow peer under a large stream, a framework that closes an app's
socket, a port already taken, a run of more than `max_entries` exchanges,
or a scheduling gap of a few microseconds. Not one is wrong data in a
normal debug session, and the plan closes without a seventh task.

## Trigger

The next PR that touches `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ClientHandler.kt`,
`desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/Http2Relay.kt`,
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/ProxyController.kt`,
`sdk/ios/Sources/AppMonitorConnections/connections.c`,
`sdk/ios/Sources/AppMonitor/ConnectionCapture.swift` or
`scripts/e2e_ios.sh`: that PR pays the item of the file it touches.

## How it was paid

> Filled when closing.

`scripts/e2e_ios.sh` paid in `feat/ios-sdk-mocks`: the gRPC and proxy ports
are read by `logged`, which waits up to 10 s for their line as `listen` does
for `Listening on port`. The other items are open.

`ConnectionCapture.swift` paid in `fix/ios-skip-kmp-desktop-socket`: a
closed connection's `ResponseReceived` no longer sets `body_truncated`,
as `protocol/fixtures/connection_closed.bin` already showed.

`ProxyController.kt` paid in `refactor/desktop-settings-feature`
(PLAN-0012 H2.4c): the recording is built closed and opened once the
server listens, so a taken port leaves no session
(`ProxyWiringTest`'s `a taken port is a status`). The other items are open.

`ExportHar.kt` paid in `refactor/mcp-read-tools-through-domain`
(PLAN-0012 H2.5a): `max_entries` counts HTTP entries only, the rows read
a page at a time past the connections, which `log.comment` still counts
(`ConnectionToolsTest.aConnectionDoesNotCountAgainstMaxEntries`, which
fails on the earlier code). The other items are open.
