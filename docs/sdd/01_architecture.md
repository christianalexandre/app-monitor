# 01 — Architecture

The target architecture agreed before the first line of code. Parts not yet
built are marked **(planned)**; see `../plans/` for their milestone.

## Overview

```
┌──────────── Device / emulator ────────────┐        ┌──────────── Desktop (Compose MP / JVM) ─────────────┐
│ Developer's app                            │        │                                                     │
│  ├─ sdk-ios (Swift)                        │  WS +  │  Server (Ktor WebSocket) ──► Ingest ──► SQLite      │
│  ├─ sdk-android (OkHttp)                   │ ─────► │        ▲ advertises mDNS       │       (SQLDelight) │
│  └─ sdk-ktor (KMP)                         │ proto  │        │                       ▼                    │
│        └── sdk-core (KMP) / protocol       │        │  Compose UI ◄── StateFlow    MCP server (localhost) │
└────────────────────────────────────────────┘        └──────────────────────────────▲──────────────────────┘
                                                                  AI agents (Claude…) ┘
```

Built: `protocol`, `desktop/server`, `desktop/app` (in-memory),
`tools/fake-client`. Everything else is **(planned)**.

## Protocol

Protobuf messages wrapped in an `Envelope` with a `channel` (`network`, later
`logs`, `analytics`, `db`, …) so new debug tools plug in without changing the
transport. Generated with Wire (Kotlin) and swift-protobuf (Swift).
Bidirectional from day one. See ADR-0001.

Schema `appmonitor.v1` in `protocol/proto/` (ADR-0008):

| Channel | Messages |
| --- | --- |
| `core` | `Hello` (device → desktop, first frame), `HelloAck` |
| `network` | `RequestStarted`, `ResponseReceived`, `RequestFailed`, `WebSocketFrame` |

`Envelope.type` names the message inside the channel; `Envelope.timestamp_ms`
is when the event happened on the sender. Constants shared by both ends
(`Protocol.VERSION`, channel names, default port, WebSocket path, mDNS
service type) live in `appmonitor.protocol.Protocol`.
Golden binary fixtures in `protocol/fixtures/` pin the encoding.

## Connection

The **desktop is the server**; devices are clients. Discovery order on the
device: manual host → mDNS (`_appmonitor._tcp`) → `10.0.2.2` (Android
emulator) / `localhost` (iOS simulator). See ADR-0002.

`desktop/server` (`AppMonitorServer`) is a Ktor CIO WebSocket server on port
`47474` (`Protocol.DEFAULT_PORT`), path `/v1/ws`. It advertises
`_appmonitor._tcp` with JmDNS on every up, multicast-capable LAN IPv4
address; TXT keys `v` (protocol version) and `path`. See ADR-0009.
Advertising is all or nothing: if one address fails to register, the
instances already created are closed and `start()` stops the HTTP server
before rethrowing, so the port is free again.

```
device                         AppMonitorServer
  │── Envelope(core, Hello) ──►│ version ≠ Protocol.VERSION, not Hello, or none in 10 s
  │                            │   → close 1002 (protocol error)
  │◄─ Envelope(core, HelloAck) │ session_id, accepted channels (core, network)
  │── Envelope(…) ×N ─────────►│ → DeviceSession.envelopes
```

- `sessions: Flow<DeviceSession>`: every handshaken connection, once, in order.
- `DeviceSession.envelopes: Flow<Envelope>`: every envelope after `Hello`,
  in order; completes when the socket closes. A non-binary or undecodable
  frame closes the connection with 1002.
- Both flows are bounded/single-collector: a lagging collector stops socket
  reads (TCP back-pressure), nothing is dropped.
- `AppMonitorServer` is single use: `stop()` completes `sessions` for good,
  and `start()` after `stop()` throws; create a new instance.
- `stop()` closes every mDNS registration, frees the port and completes
  `sessions` even when a registration fails to close; it then rethrows the
  first failure (the others suppressed).
- `start()` that fails or is cancelled (even mid-advertising) closes what it
  already registered and frees the port before rethrowing; a failure while
  releasing is suppressed on the original one.
- `stop()` during an in-flight `start()` wins: `start()` closes the
  advertiser it was creating, frees the port and throws, and `stop()` waits
  for that before returning, so the port is free once `stop()` returns. The
  engine and advertiser are published only when `start()` completes.
- A connection that finishes its handshake after `stop()` is closed with
  1001 (going away) instead of failing on the closed `sessions`.
- A connection that sends no frame within 10 s of opening is closed with 1002.

### Without the desktop UI

`desktop/server` has a headless `main` (`make server`) that prints one line
per session connect/disconnect and per envelope, decoded for the `network`
messages. `tools/fake-client` (`make fake-traffic`) plays a device: it finds
the desktop over mDNS (or `--host`), sends `protocol/fixtures/hello.bin`,
waits for `HelloAck`, sends the four `network` fixtures stamped with the
current time and closes. Any other first reply, or none within 10 s, closes
the socket with `PROTOCOL_ERROR` and exits 1 with what the desktop sent; a
`HelloAck` whose payload does not decode counts as another reply. A desktop
that closes or drops before `HelloAck` fails within the same 10 s, even if
Ktor never completes the close reason. Any other failure (unresolvable host,
non-WebSocket endpoint, mDNS bind, connection dropped while sending) exits 1
with one line on stderr, never a stack trace. The close reason is cut to the 123
UTF-8 bytes RFC 6455 allows. Bad arguments (`--timeout` of 0 or less, a
`--port` out of range: 1–65535 for the fake client, 0–65535 for the server)
print the usage line and exit 2; `--help` is only an option, never the value
of one (`--name --help` names a service). A server port that cannot be bound is still
a stack trace (DEBT-0010). See ADR-0010.

## SDKs

- **iOS**: native Swift package, no Kotlin runtime. See ADR-0003.
- **Android / KMP**: `sdk-core` in KMP; adapters for OkHttp and Ktor.
- Capture coverage on iOS is layered (URLSession → adapters → connection
  metadata → desktop MITM proxy). See ADR-0004.

## Desktop

Compose Multiplatform on the JVM. Ktor server + JmDNS, SQLDelight storage
**(planned)**, MCP server bound to `127.0.0.1` **(planned)**. Each debug tool
is a feature module owning a `channel` **(planned)**. See ADR-0005.

`desktop/app` today (ADR-0011):

```
AppMonitorServer.sessions ──► TrafficStore.collect ──► StateFlow<TrafficState> ──► AppMonitorApp
                                 one coroutine per session;     devices + HTTP exchanges,
                                 TrafficState.received(envelope) immutable snapshots
```

- `main` starts `AppMonitorServer` with the default config and feeds
  `TrafficStore` through `runServer`; the window shows "Listening on port N",
  or "Server failed" with any startup or session failure, `Error`s included
  (a `VirtualMachineError` is rethrown after it).
  A failing `stop()` shows "Server stop failed" instead of escaping the
  server coroutine or hiding the first failure.
- Exit: `runServer` runs beside `awaitApplication`, not inside the
  composition; `runUntilExit` cancels it when the last window closes and
  waits for its `stop()` (mDNS goodbye, port release) before `main` calls
  `exitProcess`. The headless server (`make server`) registers its shutdown
  hook before `start`, so a SIGINT during startup still stops the server.
- `TrafficState.received` turns `RequestStarted` / `ResponseReceived` /
  `RequestFailed` into one `HttpExchange` per `(session, request_id)`;
  duration is the difference of the envelopes' `timestamp_ms`. Other
  channels, other types and undecodable payloads leave the state unchanged.
- A device stays in the list after it disconnects, with its traffic.
- UI: three panes (devices, requests of the selected device, detail). Bodies
  open as JSON when the `Content-Type` says JSON and it parses as strict JSON
  (no bare words at any depth), as text when it is UTF-8, else as hex; the
  user can switch. The chosen format belongs to the exchange: another
  exchange with the same bytes opens in its own default. JSON is re-indented from the original text in one linear
  pass, never re-encoded. A `DecodedBody` decodes UTF-8 and parses JSON at
  most once per body, shared by the default-format choice and rendering; each
  body is rendered once per format. The hex dump is one linear pass.

## Limits

- The desktop store is in memory and unbounded; it lives until the app
  closes (SQLDelight in M5).
- WebSocket frames are received but not shown (DEBT-0003).
- Bodies render on the UI thread, whole and unbounded (DEBT-0006).

Debug builds only; release builds use no-op artifacts. Device and desktop must
share a network (or USB/emulator loopback).
