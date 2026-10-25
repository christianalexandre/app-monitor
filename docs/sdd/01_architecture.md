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

Built: `protocol`, `desktop/server`, `desktop/storage`, `desktop/mcp` (SDD 10),
`desktop/app` (on SQLite),
`tools/fake-client`, `tools/e2e-target`, `sdk/core` (JVM, Android, iOS),
`sdk/android-okhttp`, the Ktor plugin (`sdk/ktor`, HTTP and WebSocket
frames), the no-op modules (`sdk/*-no-op`), the iOS SDK (`sdk/ios`) and the
samples (`samples/android`, `samples/ios`, `samples/kmp`).
Everything else is **(planned)**.

## Protocol

Protobuf messages wrapped in an `Envelope` with a `channel` (`network`, later
`logs`, `analytics`, `db`, …) so new debug tools plug in without changing the
transport. Generated with Wire (Kotlin) and swift-protobuf (Swift).
Bidirectional from day one. See ADR-0001.

Schema `appmonitor.v1` in `protocol/proto/` (ADR-0008):

| Channel | Messages |
| --- | --- |
| `core` | `Hello` (device → desktop, first frame; `sdk_name` `kotlin` or `swift`, ADR-0077), `HelloAck` |
| `network` | `RequestStarted`, `ResponseReceived`, `RequestFailed`, `WebSocketFrame` |
| `interaction` | `MockRules`, `BreakpointRules`, `BreakpointResume`, `BreakpointAbort` (desktop → device); `BreakpointHit` (device → desktop) (SDD 12, ADR-0113, ADR-0118) |

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
  │◄─ Envelope(core, HelloAck) │ session_id, accepted channels (core, network, interaction)
  │── Envelope(…) ×N ─────────►│ → DeviceSession.envelopes
  │◄─ Envelope(…) ×N ──────────│ ← DeviceSession.send, after HelloAck only
  │                            │ channel not accepted, or a known network event
  │                            │   undecodable / without request_id → close 1002
  │                            │ frame > Protocol.MAX_FRAME_BYTES → close 1009
  │◄─ ping every 15 s ─────────│ no pong within 15 s → close 1011, session ends
```

- `sessions: Flow<DeviceSession>`: every handshaken connection, once, in order.
- `DeviceSession.envelopes: Flow<Envelope>`: every envelope after `Hello`,
  in order; completes when the socket closes. A non-binary or undecodable
  frame closes the connection with 1002.
- `DeviceSession.send(envelope)`: queues an envelope for the device on an
  accepted channel, written in order after `HelloAck` (SDD 12).
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
  connections of the same pair, and remembers each answer, starting from
  the ones it is built with, until `forget` (persisted by the app, below).
  `AppMonitorServer.disconnect(key)` closes the pair's connections with
  1008, handshakes still pending or answered but not yet published
  included; under the lock that publishes sessions, so a connection racing
  a forget either is closed after `HelloAck` or never gets one. An answered
  handshake found forgotten sends the 1008 itself before its handler
  returns, so Ktor's normal 1000 close never wins.
  `DeviceApprovals.disconnect(hello)` refuses the pair at once, whatever its
  answer and without a pending entry (connections still pending are
  answered with a refusal it does not remember), until `reconnect(key)`;
  `disconnected` lists the pairs with the `Hello` given, in memory only
  (ADR-0144).
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
messages; with `--proxy <dir>` it also runs the proxy (SDD 11) and prints
its exchanges as the `[proxy]` session. `tools/fake-client` (`make fake-traffic`) plays a device: it finds
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
- **Android / KMP**: `sdk/core` in KMP (JVM, Android `minSdk` 24, `iosArm64`
  and `iosSimulatorArm64`; Kotlin/Native specifics in SDD 04, ADR-0066 to
  ADR-0068); the
  OkHttp adapter `sdk/android-okhttp` (SDD 05, ADR-0025); Ktor plugin
  `sdk/ktor` on JVM, Android and iOS, HTTP and WebSocket frames (SDD 08, ADR-0069, ADR-0070). Connection states, offline buffer and `stop()`: SDD 04
  (ADR-0022); module layout and Ktor CIO transport: ADR-0023.
- **Android entry point** (ADR-0017): `AppMonitor.start(context, options)` /
  `stop()`; `AppMonitorOptions` holds the enabled flag, manual host and port,
  app name, masked headers and query parameters, `maxBodyBytes` and the
  `masking` switch (ADR-0034). Masking
  and the body limit run on the device, before the wire (ADR-0018). Capture:
  `AppMonitorInterceptor` and `AppMonitorWebSocketFactory`.
- **KMP entry point** (ADR-0062): `install(AppMonitorKtor)` on each
  `HttpClient`, no configuration block, options from the running monitor;
  `AppMonitor.start(context, options)` on Android and
  `AppMonitor.start(options)` from `iosMain`, no common `expect`. Ktor 3.6.0
  or newer. Masking as ADR-0063.
- **Dedupe on iOS** (ADR-0003, ADR-0064, ADR-0071): an app running both
  `sdk/ktor` and the Swift SDK shares one device id, so the desktop keeps two
  sessions for one (`device_id`, `app_id`); `sdk/ktor` sets
  `X-AppMonitor-Capture` on what it captured and the Swift SDK's hooks skip
  those tasks, so each exchange shows once. On the JVM and Android, beside
  `appmonitor-okhttp`, the same mark makes `AppMonitorInterceptor` skip
  Ktor's OkHttp engine calls and take the header off (ADR-0129).
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
- `samples/ios` uses the same product, from the root `Package.swift`
  (ADR-0109), in both configurations; its Release
  app names nothing of the `AppMonitor` module but the two stubs, and only
  its Debug app has the local network keys (`make ios-sample`, ADR-0050).
- `samples/kmp` (Compose Multiplatform, Android and iOS) calls through Ktor
  with `install(AppMonitorKtor)`; its iOS host also starts the Swift SDK.
  Release builds swap the SDK for its no-ops (ADR-0062 D.1): on iOS the
  shared module's Gradle script picks `sdk/ktor-no-op` from Xcode's
  `CONFIGURATION`, on Android the release app substitutes `sdk/ktor` and
  `sdk/core`; `make kmp-sample` checks both releases hold no SDK name
  (ADR-0073).
- Capture coverage on iOS is layered (URLSession → adapters → connection
  metadata → desktop MITM proxy). See ADR-0004.

## Desktop

Compose Multiplatform on the JVM. Ktor server + JmDNS, SQLDelight storage
(`desktop/storage`, SDD 09), MCP server bound to `127.0.0.1` (`desktop/mcp`,
SDD 10), proxy on `127.0.0.1`, off by default (`desktop/proxy`, SDD 11). Each debug tool
is a feature module owning a `channel` **(planned)**. See ADR-0005.

`desktop/app` today (ADR-0011):

```
AppMonitorServer.sessions ──► TrafficStore.collect ──► Recorder ──► LiveTraffic (SDD 09) ──► observed reads ──► AppMonitorApp
                                 one coroutine per session;  │          in memory,           sessions, a page
                                 connected / received /      │          bounded; stored      of exchanges, one
                                 disconnected                │          runs from Storage    exchange's detail
                                                             └─► Storage, what starts while recording (ADR-0142)
```

- `main` opens `Storage` in `dataDirectory()` first; when that fails the
  window shows the error and the file's path and no server starts (SDD 09).
  Then it starts `AppMonitorServer` with the default config and feeds
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
  After the server, `once { storage.close(STOP_TIMEOUT) }` ends the run and
  drains the write queue, from `main` or from the hook, whose bound is
  then two `STOP_TIMEOUT`s. A session's `Disconnected` waits at most
  `STOP_TIMEOUT` for room in the queue, so a stalled disk cannot hold the exit.
- Storage turns `RequestStarted` / `ResponseReceived` / `RequestFailed`
  into one exchange per `(session, request_id)` and each `WebSocketFrame`
  into a frame of the exchange of its `request_id` (SDD 09); duration is the
  difference of the envelopes' `timestamp_ms`. Other channels, other types
  and undecodable payloads are kept apart and not shown.
- Live list and runs (ADR-0140): the window opens on the live list
  ("Live ▾"), every device's traffic since the last "Clear", in memory and
  not stored (SDD 09). The device list shows its sessions, oldest first,
  "connected" until they end, under "All devices"; "All devices" (the
  default) lists every device's requests in one list, a device only its
  own. Above the request list, on the live list only: "Record" / "Stop
  recording" (ADR-0142), "Save" (or "Save N") which stores the ticked
  requests, or the selected one, in "Saved" and says how many it saved
  (ADR-0143), and "Clear", which empties the live list. The picker opens
  "Live", "Saved" once something is saved, then the recordings, newest
  first: each opens read-only, on all its devices; "Rename" names a
  recording (blank makes it unnamed again). No run opens at launch.
- Reading: every pane follows the live list's reads, run again after each
  change, or a stored run's SQLDelight query flows, re-run after each
  commit that touches their table. The request list is the device's, or
  every device's, first `PAGE_SIZE` rows by id; scrolling within 50 rows of the end grows that
  limit by one page. The detail reads the selected exchange, its bodies and
  its first `PAGE_SIZE` frames ("Show more frames" reads one more page)
  again whenever its list row changes. A write failure shows
  "N events not saved: <error>" under the status.
- Issues (ADR-0095, ADR-0096): each request row has a checkbox; "Save as
  issue" (or "Save N as issue") above the list takes the ticked requests,
  from any session or run, or the selected one (live ones are saved to
  "Saved" first, ADR-0143), and asks a title (the
  first request's method and URL by default), a note and comma-separated
  tags. "Issues" beside the run picker lists them newest first ("Open" or
  "Resolved", tags, request count, "in the window" or "by MCP", last
  change) with a status and a tag filter; one opens to edit its title,
  note, tags and status, lists its requests, and "Delete" removes it after
  a confirmation. The editor sends only the fields changed since it opened
  (SDD 09 lists every writer and their order), so an MCP edit of another
  field meanwhile is kept; the tags count as changed only when their field
  is edited, so an MCP tag holding a comma survives an edit of another field.
- Benchmarks (ADR-0097): "Benchmarks" above the request list opens, for
  every device of the shown run (or every run) under the request filter,
  one row per endpoint: count, status classes, error rate, p50, p95, p99,
  sent and received stored body bytes, most called first; a column header
  sorts by it, again reverses. The numbers are `endpoint_stats`' (SDD 10),
  read once when opened and on "Refresh", not on each commit: a read pages
  through up to 100 000 exchanges, and says so when it stops there.
  With a run shown, "By device" (ADR-0140 D) splits the same rows into one
  group of columns per device of the run, in the order they joined it:
  count, status classes, p50, p95 and received bytes, `—` where a device
  never called the endpoint; "By endpoint" goes back. A device reconnected
  during the run is one column.
- Run diff (ADR-0097): "Compare runs" in the run list (two runs or more)
  opens a base and a run picked from every run (the previous and the
  newest first), each by title and id, under the request filter for both.
  It shows the counts added, gone, changed and unchanged, then the changed
  endpoints (count, error rate, p50, p95 as `base → run`), the added and
  the gone ones (the one side's numbers). The comparison is `diff_runs`'
  (`diffEndpoints`, SDD 09), read on each pick and on "Refresh".
- Export (ADR-0098): "Export HAR" above the request list asks a file in
  the native save dialog and writes `export_har`'s HAR (SDD 10) of every
  device of the shown run (or every run) under the request filter: the
  first 1 000 exchanges, oldest first, bodies whole, refused past 32 MiB;
  a line under the buttons says how many were written, or why not, and
  how many connections were left out. "Copy as cURL" in the request detail
  puts `to_curl`'s command for that exchange on the clipboard and says
  "Copied". Both go through `desktop/mcp`'s `Exports`.
- Connections (ADR-0104): a `CONNECTION` exchange's row shows `CONNECT`,
  `tcp://host:port` and `open`, `closed` or `failed` as its status; its
  detail shows the layer caption, then bytes sent and received instead of
  headers and bodies, with no "Copy as cURL". Benchmarks and the run diff
  list it as `CONNECT host:port` (`connections.png`).
- gRPC status (ADR-0107): a call whose `grpc_status` is other than 0 shows
  `grpc N` as its status in the row and the detail, and counts as an error
  in benchmarks and the run diff.
- Approval: `DeviceApprovals.pending`, read as `PendingApproval`s through
  `ApprovalStore` and `DecisionsViewModel`, drives a panel over the three panes,
  one device at a time ("<device_name> — <app_id> wants to connect", OS, app
  version, device id, "N more waiting"); Approve / Reject call
  `ApprovalStore.decide`, which answers the connections at once and queues
  the answer to storage (ADR-0083). The store is built from the stored
  answers at launch, so an approved pair is admitted with no dialog and a
  rejected one closed with 1008 after a restart. "Settings" above the device
  list opens the settings; their "Devices" section lists every answered pair
  (name, app id, approved or rejected, date, OS, app version) with "Forget"
  per row and "Forget all": a forgotten pair is asked again on its next
  `Hello`, and its open sessions are closed with 1008 when it was approved.
  A pending device is not in the device list until approved.
  A connected session in the live list has "Disconnect" (ADR-0140 E,
  ADR-0144): `ApprovalStore`, as `DeviceConnectionRepository`, refuses the
  pair first and then closes its sessions with 1008; its next connections
  are closed with 1008 with no dialog. Disconnected pairs are listed under
  "Disconnected" below the devices, with "Reconnect", which lets the pair
  in again as its answer says. Nothing of it is stored: a restart
  reconnects every pair. The SDK keeps retrying with its backoff, as for a
  rejected device.
  `make fake-traffic` against the app must be approved within its 10 s
  handshake wait the first time; its fixture `Hello` is always the same
  pair, so later runs are admitted with no dialog (DEBT-0028).
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
  release (`make samples`, ADR-0076); the Android sample on an emulator, the iOS
  sample on a simulator and the KMP sample on both, each against the
  headless server and `tools/e2e-target`, every envelope asserted and every
  exchange captured once (ADR-0056, ADR-0057, ADR-0074, ADR-0075); unsigned `.dmg`, `.msi`, `.deb` (ADR-0055);
  the SDK publication dry-run (ADR-0058). The `protect-main` ruleset makes
  every gate job a required check (ADR-0053).

## Limits

- WebSocket ping and pong frames are not captured (DEBT-0029).
- Selecting text across body chunks that scrolled out of view is lost
  (ADR-0029).

Debug builds only; release builds use no-op artifacts. Device and desktop must
share a network (or USB/emulator loopback).
