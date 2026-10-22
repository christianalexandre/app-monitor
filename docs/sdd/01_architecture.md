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

```
device                         AppMonitorServer
  │── Envelope(core, Hello) ──►│ version ≠ Protocol.VERSION, or not Hello
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

### Without the desktop UI

`desktop/server` has a headless `main` (`make server`) that prints one line
per session connect/disconnect and per envelope, decoded for the `network`
messages. `tools/fake-client` (`make fake-traffic`) plays a device: it finds
the desktop over mDNS (or `--host`), sends `protocol/fixtures/hello.bin`,
waits for `HelloAck`, sends the four `network` fixtures stamped with the
current time and closes. See ADR-0010.

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
  `TrafficStore`; the window shows "Listening on port N" or the start error.
- `TrafficState.received` turns `RequestStarted` / `ResponseReceived` /
  `RequestFailed` into one `HttpExchange` per `(session, request_id)`;
  duration is the difference of the envelopes' `timestamp_ms`. Other
  channels, other types and undecodable payloads leave the state unchanged.
- A device stays in the list after it disconnects, with its traffic.
- UI: three panes (devices, requests of the selected device, detail). Bodies
  open as JSON when the `Content-Type` says JSON and it parses, as text when
  it is UTF-8, else as hex; the user can switch. JSON is re-indented from the
  original text, never re-encoded.

## Limits

- The desktop store is in memory and unbounded; it lives until the app
  closes (SQLDelight in M5).
- WebSocket frames are received but not shown (DEBT-0003).

Debug builds only; release builds use no-op artifacts. Device and desktop must
share a network (or USB/emulator loopback).
