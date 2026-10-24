# 06 — iOS SDK connection lifecycle

How the Swift transport in `sdk/ios` finds the desktop, connects, holds
events and lets go of everything on `stop()`. It runs SDD 04's state
machine — same states, bounds, backoff and buffer policy (ADR-0022,
ADR-0024) — on Network.framework and one serial queue (ADR-0040). This
chapter lists only where it differs from `sdk/core`. The transport is
`internal` `MonitorClient` in `sdk/ios`, fed by `discovery(...)` and held
by `MonitorHolder` behind the public `AppMonitor`.

## How it works

`AppMonitor.start(_:)` / `stop()` are static, callable from any thread
(ADR-0035 A). `start()` builds one **client** with its own serial
`DispatchQueue`; `NWConnection`, `NWBrowser` and every timer deliver on it,
so states change one at a time, in order. Only the buffer is touched from
the app's threads, behind an `NSLock`.

### States and their Swift exits

| State | Doing | Exits |
| --- | --- | --- |
| **idle** | No client. Events dropped. | `start()` with `enabled` → discovering. `enabled = false` → stays idle. |
| **discovering** | Manual `host` only, else `NWBrowser` (`bonjourWithTXTRecord`, `_appmonitor._tcp`), then `127.0.0.1:47474` on the simulator (`targetEnvironment(simulator)`, ADR-0002), as in [Discovery](#discovery). | An endpoint → connecting. Nothing within 5 s → backing off. `stop()` → stopped. |
| **connecting** | `NWConnection` to `NWEndpoint.url(ws://host:port/path)` with `NWProtocolWebSocket` over TCP; a 10 s timer on the queue. | `.ready` → handshaking. `.failed`, `.cancelled`, `.waiting` with ECONNREFUSED (nothing listens there, ADR-0041) or the 10 s timer → backing off; the first such failure of a client logs one line naming the endpoint and the Info.plist keys (ADR-0036 E). Any other `.waiting` is not an exit: the local network prompt may be up (ADR-0040). `stop()` → stopped. No cleartext exit: ATS does not govern Network.framework. |
| **handshaking** | Sends `Hello` as the first message, waits for `HelloAck` with no timeout; the pinger runs. | `HelloAck` → connected. Close 1008 → backing off at the longest delay. Any other close, `.failed`, or no pong within 30 s → backing off. `stop()` → stopped. |
| **connected** | Sends the buffer in order, then each new event; envelopes on a channel missing from `HelloAck.channels` are dropped. One `send` in flight at a time; the next starts from its completion. | Close from the desktop, `.failed`, a `send` completion with an error, or no pong within 30 s → backing off. `stop()` → stopped. |
| **backing off** | `DispatchSourceTimer`: 1 s, 2, 4… up to 30 s, ±20 % jitter; reset once a session reaches connected. | Timer → discovering. `stop()` → stopped. |
| **stopped** | Every resource released, buffer discarded. Events dropped. | `start()` → a new client, empty buffer. |

`start()` while a client runs is ignored, as on Android.

### Discovery

| Source | Used when | Gives up |
| --- | --- | --- |
| Manual `host`, `port` | `host` set; nothing else is tried | Never |
| `NWBrowser` | No `host` | On the simulator after 3 s; on a device at the 5 s bound |
| `127.0.0.1:47474` | On the simulator and the browser found nothing: the simulator shares the Mac's network stack (ADR-0002) | Never |

Answers go through the same selection rule as `DesktopSelector` (ADR-0024):
first desktop answered with an IPv4 address kept for the round, names
compared case-insensitively, TXT `path` waited for up to 500 ms, then
`/v1/ws`. A browser in `.waiting` with `kDNSServiceErr_PolicyDenied`, or
`.failed`, logs one line per client naming `NSLocalNetworkUsageDescription`
and `NSBonjourServices`, and counts as found nothing.

`NWBrowser` gives a name and a TXT record, no address. `BonjourBrowser`
opens one IPv4-only `NWConnection` to each new name's `.service` endpoint,
reads `currentPath.remoteEndpoint` at `.ready` and cancels it (ADR-0042);
the answer goes to the selector with that address and the latest TXT
`path`, and again on every later TXT change. A probe that fails is retried
on the name's next result; every probe ends with the round. The desktop
sees that probe as one empty TCP connection.

The device id file (ADR-0036 D) is read or created on the client's queue,
not on the thread that called `start()`. A new id is written beside the file
and hard linked to it, so the Swift SDK and `sdk/core` in one app keep the
first one written (ADR-0078).

### Info.plist keys

On a device both the TCP connection and `NWBrowser` need
`NSLocalNetworkUsageDescription` and `NSBonjourServices` containing
`_appmonitor._tcp` (ADR-0036 E). `sdk/ios/scripts/add_local_network_keys.sh`,
run by an app's Run Script phase after its other phases, adds them to the
built `Info.plist` when the configuration name contains `Debug`, the same
configurations that compile the SDK (ADR-0048), and leaves other builds
untouched (ADR-0049). It adds the usage text only when absent
(`APPMONITOR_LOCAL_NETWORK_USAGE` overrides the default) and appends the
service only when missing. With `ENABLE_USER_SCRIPT_SANDBOXING`, the phase
declares the script and `$(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)` as inputs,
and that path plus `$(DERIVED_FILE_DIR)/appmonitor-local-network-keys.stamp`
as outputs; `sdk/ios/TestHost/project.yml` is the reference.

### Resources and their owners

| Resource | Owner | Lives |
| --- | --- | --- |
| Serial `DispatchQueue` | client | `start()` → `stop()`; every resource below delivers on it |
| Ring buffer (`NSLock`) | client | `start()` → `stop()`; survives reconnects |
| Device id, `Hello` | client | built once, on the queue, at the first handshake |
| `NWBrowser`, its probe `NWConnection`s, 500 ms TXT timer, 3 s / 5 s bound | discovering | cancelled on every exit of discovering |
| `NWConnection` | connecting | handed to handshaking, then connected; cancelled on any exit to backing off or stopped |
| 10 s connect timer | connecting | cancelled on exit |
| Ping timer (15 s) and pong deadline (30 s) | handshaking | handed to connected; cancelled with the connection |
| Backoff timer | backing off | cancelled on exit |

Every handler checks that its connection or browser is still the current
one before acting, so a callback that was already queued when its owner was
cancelled does nothing.

### `stop()` in each state

`stop()` returns at once; the release runs on the client's queue, bounded
by 1 s. Tests await it through an internal async join.

| State | What `stop()` releases |
| --- | --- |
| idle, stopped | Nothing. |
| discovering | `NWBrowser.cancel()`, TXT and bound timers. |
| connecting | Connect timer; `NWConnection.cancel()`. |
| handshaking, connected | Ping timers; sends close 1000 and waits up to 1 s for its completion, then `NWConnection.cancel()`; `forceCancel()` if the 1 s runs out. Buffered events are not flushed. |
| backing off | Backoff timer. |

In every state it then discards the buffer; the client and its queue are
released once the last handler has run.

### Buffer policy

SDD 04's, unchanged: any thread, never blocks beyond the lock, 4096
envelopes and 8 MiB, oldest dropped, envelopes over
`Protocol.MAX_FRAME_BYTES` dropped before entering, at most once, memory
only.

## Limits

- Everything in SDD 04's limits applies, DEBT-0020, DEBT-0023 and DEBT-0024
  included.
- A suspended app runs no timers: a connection the OS dropped meanwhile is
  noticed when the app resumes, by `.failed` or the pong deadline.
- Local network privacy exists only on a device (TN3179, ADR-0036): the
  `PolicyDenied` and `.waiting` paths are proven in M2.13, not in
  `swift test`.
