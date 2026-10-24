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
`tools/fake-client`, the Android SDK (`sdk/core`, `sdk/android-okhttp`),
the no-op modules (`sdk/*-no-op`), the Ktor plugin (`sdk/ktor`, HTTP), `samples/android`, the iOS SDK (`sdk/ios`) and `samples/ios`.
Everything else is **(planned)**.

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
`RequestStarted` and `ResponseReceived` bodies hold at most the sender's
body limit: `body_truncated` marks a cut or unread body, `body_size` (absent
when unknown) is the original size (ADR-0020).
`RequestStarted.capture_layer` names the ADR-0004 layer that produced the
exchange (`PLATFORM` URLSession, `ADAPTER` OkHttp/Ktor/grpc-swift,
`CONNECTION`, `PROXY`); every later event of the `request_id` inherits it,
and an older or unknown value reads as `UNSPECIFIED` (ADR-0038).
Golden binary fixtures in `protocol/fixtures/` pin the encoding.

## Connection

The **desktop is the server**; devices are clients. Discovery order on the
device: manual host → mDNS (`_appmonitor._tcp`) → `10.0.2.2` (Android
emulator) / `127.0.0.1` (iOS simulator). See ADR-0002.

`desktop/server` (`AppMonitorServer`) is a Ktor CIO WebSocket server on port
`47474` (`Protocol.DEFAULT_PORT`), path `/v1/ws`. It advertises
`_appmonitor._tcp` with JmDNS on every up, multicast-capable LAN IPv4
address (point-to-point interfaces such as VPN tunnels excluded); TXT keys `v` (protocol version) and `path`. See ADR-0009.
Advertising is all or nothing: if one address fails to register (an `Error`
included), the instances already created are closed and `start()` stops the
HTTP server before rethrowing, so the port is free again.

```
device                         AppMonitorServer
  │── Envelope(core, Hello) ──►│ version ≠ Protocol.VERSION, not Hello, or none in 10 s
  │                            │   → close 1002 (protocol error)
  │                            │ pending: DeviceApproval.approve(hello), no timeout
  │                            │   rejected → close 1008; any frame → close 1002
  │◄─ Envelope(core, HelloAck) │ session_id, accepted channels (core, network)
  │── Envelope(…) ×N ─────────►│ → DeviceSession.envelopes
  │                            │ channel not accepted, or a known network event
  │                            │   undecodable / without request_id → close 1002
  │                            │ frame > Protocol.MAX_FRAME_BYTES → close 1009
  │◄─ ping every 15 s ─────────│ no pong within 15 s → close 1011, session ends
```

- `sessions: Flow<DeviceSession>`: every handshaken connection, once, in order.
- `DeviceSession.envelopes: Flow<Envelope>`: every envelope after `Hello`,
  in order; completes when the socket closes. A non-binary or undecodable
  frame closes the connection with 1002.
- Both flows are bounded/single-collector: a lagging collector stops socket
  reads (TCP back-pressure), nothing is dropped.
- `AppMonitorServer` is single use: `stop()` completes `sessions` for good,
  and `start()` after `stop()` throws before building an engine; create a
  new instance.
- `stop()` closes every mDNS registration, frees the port and completes
  `sessions` even when a registration fails to close (an `Error` included);
  it then rethrows the first failure (the others suppressed).
- `start()` that fails or is cancelled (even mid-advertising) closes what it
  already registered and frees the port before rethrowing; a failure while
  releasing is suppressed on the original one.
- `stop()` during an in-flight `start()` wins: `start()` closes the
  advertiser it was creating, frees the port and throws, and `stop()` waits
  for that before returning, so the port is free once `stop()` returns. If
  `start()`'s mDNS close fails, `stop()` rethrows that failure too. The
  engine and advertiser are published only when `start()` completes. This
  holds when `stop()` arrives while a failed `start()` is still releasing.
- A second `stop()` (e.g. the headless server's shutdown hook) waits for
  the first one to finish releasing instead of returning at once, and
  rethrows the same release failure, so a failure is still reported when the
  first caller was cancelled.
- The release runs on its own coroutine (`Dispatchers.IO`), not the caller's:
  a `stop()` cancelled mid-close (the app's `withTimeout`) returns at once,
  but the mDNS close and port release still finish, and a later `stop()`
  waits for them.
- A connection that finishes its handshake after `stop()` began gets no
  `HelloAck`: it is closed with 1001 (going away). The session is published
  before `HelloAck` is sent, under the lock `stop()` takes, so a device that
  got `HelloAck` is always on `sessions`.
- A connection that sends no frame within 10 s of opening is closed with 1002.
- Approval (ADR-0018 E, ADR-0027): after a valid `Hello` the server asks its
  `DeviceApproval` and waits without a timeout; pings keep running. Approved
  → the session is published and gets `HelloAck`; rejected → close 1008. A
  frame sent while pending closes with 1002; a device that closes or misses
  its ping while pending is withdrawn. The default approves every device
  (headless server, tests); the desktop app passes `DeviceApprovals`, which
  asks once per (`device_id`, `app_id`), shares one pending entry between
  connections of the same pair, and remembers each answer until the app
  quits (persisted in M5).
- Envelope rules (ADR-0021): an envelope on a channel not accepted in
  `HelloAck`, or a `RequestStarted` / `ResponseReceived` / `RequestFailed` /
  `WebSocketFrame` whose payload does not decode or has an empty
  `request_id`, closes the connection with 1002; every earlier envelope is
  still delivered. Unknown `network` types and other accepted channels pass
  undecoded.
- Frames are read up to `Protocol.MAX_FRAME_BYTES`: one
  `Protocol.MAX_BODY_BYTES` (1 MiB) body plus 256 KiB for URL, headers and
  envelope. A larger frame is refused from its header and closes with 1009;
  a client still writing that frame may see the reset (1006) instead.
- Keep-alive: the server pings every 15 s; without the pong within 15 s it
  closes with 1011 and the session's `envelopes` completes, so a device gone
  from Wi-Fi ends within ~30 s. Pongs are read behind data frames: a
  collector stalled longer than the timeout ends a healthy session
  (DEBT-0022).

### Without the desktop UI

`desktop/server` has a headless `main` (`make server`) that prints one line
per session connect/disconnect and per envelope, decoded for the `network`
messages. `tools/fake-client` (`make fake-traffic`) plays a device: it finds
the desktop over mDNS on every LAN IPv4 address, skipping one where JmDNS
cannot open (or at `--host`), sends `protocol/fixtures/hello.bin`,
waits for `HelloAck`, sends the four `network` fixtures stamped with the
current time and closes. Any other first reply, or none within 10 s, closes
the socket with `PROTOCOL_ERROR` and exits 1 with what the desktop sent; a
`HelloAck` whose payload does not decode counts as another reply. A desktop
that closes or drops before `HelloAck` fails within the same 10 s. After the
traffic the client sends a normal close and waits up to 10 s for the
desktop's: a close that is not `NORMAL` (e.g. `PROTOCOL_ERROR` for a rejected
frame) exits 1 with its reason; none at all counts as a dropped connection. A frame rejected after the desktop read that close still reads as `NORMAL` (DEBT-0014).
The client uses Ktor's raw CIO session, since the default one reports its own
close instead of the desktop's. Any other failure (unresolvable host,
non-WebSocket endpoint, mDNS bind on every interface, connection dropped while sending) exits 1
with one line on stderr, never a stack trace. The close reason is cut to the 123
UTF-8 bytes RFC 6455 allows. Bad arguments (`--timeout` of 0 or less, a
`--port` out of range: 1–65535 for the fake client, 0–65535 for the server)
print the usage line and exit 2; `--help` is only an option, never the value
of one (`--name --help` names a service). A server port that cannot be bound is still
a stack trace (DEBT-0010). See ADR-0010.

## SDKs

- **iOS**: native Swift package `sdk/ios`, product `AppMonitor`, iOS 15, no
  Kotlin runtime (ADR-0003, ADR-0035). Code generated by swift-protobuf from
  the same schema, checked against `protocol/fixtures/` (ADR-0039).
  Connection states, discovery and `stop()`: SDD 06 (ADR-0040 to ADR-0042).
- **iOS entry point** (ADR-0035): `AppMonitor.start(options)` / `stop()`,
  static, from any thread; `AppMonitorOptions` holds the same fields as on
  Android, the `masking` switch included (ADR-0034). Capture needs no other call:
  hooks installed at the first `start()` cover every `URLSession`, its
  WebSocket and background tasks included (SDD 07, ADR-0043, ADR-0045,
  ADR-0046). Masking and the body limit run on the device, against the
  shared `protocol/fixtures/masking.json` vector (ADR-0036). Debug builds
  need the local network keys, which
  `sdk/ios/scripts/add_local_network_keys.sh` adds (ADR-0049).
- **Android / KMP**: `sdk-core` in KMP (JVM + Android, `minSdk` 24); the
  OkHttp adapter `sdk/android-okhttp` (SDD 05, ADR-0025); Ktor plugin
  `sdk/ktor` on JVM, Android and iOS, HTTP and WebSocket frames (SDD 08, ADR-0069, ADR-0070). Connection states, offline buffer and `stop()`: SDD 04
  (ADR-0022); module layout and Ktor CIO transport: ADR-0023.
- **Android entry point** (ADR-0017): `AppMonitor.start(context, options)` /
  `stop()`; `AppMonitorOptions` holds the enabled flag, manual host and port,
  app name, masked headers and query parameters, `maxBodyBytes` and the
  `masking` switch (ADR-0034). Masking
  and the body limit run on the device, before the wire (ADR-0018). Capture:
  `AppMonitorInterceptor` and `AppMonitorWebSocketFactory`.
- **Release builds** swap each SDK module for its no-op twin
  (`sdk/core-no-op`, `sdk/okhttp-no-op`, `sdk/ktor-no-op`, ADR-0062 C.1): the
  same public types doing nothing, kept identical by comparing the committed
  JVM, Android and klib ABI dumps (ADR-0072).
- `samples/android` uses the SDK in debug and `sdk/okhttp-no-op` in release, and
  `checkReleaseHasNoSdk` proves the release APK has no SDK classes
  (ADR-0030). It is the device side of the end-to-end run (SDD 02,
  ADR-0031).
- **iOS release builds** compile the same `AppMonitor` product to stubs:
  every implementation file and the generated code sit under `#if DEBUG`,
  and both builds must match `sdk/ios/api/AppMonitor.swiftinterface`
  (ADR-0048). The package gets `DEBUG` only in an app configuration whose
  name contains `Debug`. SwiftProtobuf is still linked in release
  (DEBT-0040).
- `samples/ios` uses the same product in both configurations; its Release
  app names nothing of the `AppMonitor` module but the two stubs, and only
  its Debug app has the local network keys (`make ios-sample`, ADR-0050).
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
  "Server stopped" when `sessions` completes because someone else stopped
  the server, or "Server failed" with any startup or session failure, `Error`s included
  (a `VirtualMachineError` is rethrown after it). A `CancellationException`
  is rethrown only when the server coroutine itself is cancelled; one from
  another scope while it is still active shows "Server failed" too.
  A failing `stop()`, `Error`s included, shows "Server stop failed" instead
  of escaping the server coroutine or hiding the first failure; a `stop()`
  still running after `STOP_TIMEOUT` (5 s) shows "Server stop timed out".
- Exit: `runServer` runs beside `awaitApplication`, not inside the
  composition; `runUntilExit` cancels it when the last window closes and
  waits for its `stop()` (mDNS goodbye, port release) before `main` calls
  `exitProcess` through `exitAfter`: code 0, or 1 after printing the
  failure when the UI throws, so AWT threads cannot keep the JVM alive. The headless server (`make server`) registers its shutdown
  hook before `start`, so a SIGINT during startup still stops the server; the
  app registers one before the window opens, so SIGINT or SIGTERM stops it
  too. Both use `shutdownHook`: `stop()` runs on a daemon thread and the hook
  gives up after `STOP_TIMEOUT`, so a `start` stuck in mDNS registration
  cannot hang the exit; mDNS goodbye is then lost.
  The app's hook and `runServer` share one `once(server::stop)`: whichever
  runs first stops the server and the other returns at once, so a hanging
  `stop()` delays the exit by one `STOP_TIMEOUT` and is reported once.
- `TrafficState.received` turns `RequestStarted` / `ResponseReceived` /
  `RequestFailed` into one `HttpExchange` per `(session, request_id)`;
  duration is the difference of the envelopes' `timestamp_ms`. Each
  `WebSocketFrame` is appended, in arrival order, to the `frames` of the
  exchange of its `request_id` (the upgrade request). Other
  channels, other types and undecodable payloads leave the state unchanged.
- A device stays in the list after it disconnects, with its traffic.
- Approval: `DeviceApprovals.pending` drives a panel over the three panes,
  one device at a time ("<device_name> — <app_id> wants to connect", OS, app
  version, device id, "N more waiting"); Approve / Reject call
  `DeviceApprovals.decide`. A pending device is not in the device list until
  approved. `make fake-traffic` against the app must be approved within its
  10 s handshake wait (DEBT-0028).
- UI: three panes (devices, requests of the selected device, detail). The
  detail caption reads "Status · Duration · <Layer> layer" ("Unknown layer"
  when no `RequestStarted` or an older SDK sent it, ADR-0038). Bodies
  open as JSON when the `Content-Type` says JSON and it parses as strict JSON
  (no bare words at any depth), as text when it is UTF-8, else as hex; the
  user can switch. The chosen format belongs to the exchange: another
  exchange with the same bytes opens in its own default. JSON is re-indented from the original text in one linear
  pass, never re-encoded. A `DecodedBody` decodes UTF-8 and parses JSON at
  most once per body, shared by the default-format choice and rendering; each
  body is rendered once per format. The hex dump is one linear pass.
  A truncated body shows "Truncated: N of M bytes shown" (or "original size
  unknown") above it; an unread one shows only that note.
- Rendering (ADR-0029): the detail pane is a `LazyColumn`. Each body is
  rendered by `renderBody` on `Dispatchers.Default` ("Rendering…" until then)
  and split into chunks, one item each, so the UI thread lays out only
  visible chunks. A chunk ends at the line break after 100 lines or
  8 192 characters; only a line longer than 8 192 characters is cut inside. Indented JSON stops at
  `MAX_RENDERED_CHARS` (16 Mi) with a "… stopped at" line.
- Frames: under "WebSocket frames (N)", one row per frame: direction (↑ sent,
  ↓ received), opcode, size ("N of M B" when cut, ADR-0028), time since the
  request started, and a one-line preview (text: first 512 bytes; close: code
  and reason). Clicking a row opens its payload with the body view and format
  buttons; one frame is open at a time.

## Verification

Two levels guard the branches (details and commands in SDD 02, *CI*):

- **PRs to `dev`**: `ci.yml` runs `make check`, the fast suite, on ubuntu
  (ADR-0012, ADR-0076) and `pr-conventions.yml` checks branch, title,
  trailers and docs. Swift host gates run only locally on this level.
- **PRs to `main`**: `release-gate.yml` (ADR-0052) accepts only `dev` or
  `release/x.y.z` as head, then proves the whole system: desktop tests on
  ubuntu, macOS and windows; the Kotlin ↔ Swift contract (`make swift`,
  `make swift-sim`, `:protocol:allTests`); every sample app in debug and
  release (`make samples`, ADR-0076); the Android sample on an emulator and the iOS
  sample on a simulator, each against the headless server, every envelope
  asserted (ADR-0056, ADR-0057); unsigned `.dmg`, `.msi`, `.deb` (ADR-0055);
  the SDK publication dry-run (ADR-0058). The `protect-main` ruleset makes
  every gate job a required check (ADR-0053).

## Limits

- The desktop store is in memory and unbounded; it lives until the app
  closes (SQLDelight in M5).
- WebSocket ping and pong frames are not captured (DEBT-0029).
- Selecting text across body chunks that scrolled out of view is lost
  (ADR-0029).

Debug builds only; release builds use no-op artifacts. Device and desktop must
share a network (or USB/emulator loopback).
