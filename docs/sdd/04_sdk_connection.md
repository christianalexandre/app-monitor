# 04 — SDK connection lifecycle

How `sdk/core` (KMP, Android + JVM) finds the desktop, connects, holds
events while it cannot send them, and lets go of everything on `stop()`.
The transport is `internal` `MonitorClient` in `sdk/core` (ADR-0023), which
connects to the endpoint its `discover` function returns; discovery and the
Android `AppMonitor.start` / `stop` entry point are ADR-0024. Decisions in
ADR-0022; public API in ADR-0017; approval and masking in ADR-0018.

## How it works

`AppMonitor.start(context, options)` builds one **client**; `stop()` ends
it. The client is a state machine; exactly one state is current.

```
         start()               found               upgraded               HelloAck
  idle ─────────► discovering ───────► connecting ──────────► handshaking ────────► connected
                     ▲   │ none           │ failed                │ closed/failed      │ closed/failed
                     │   ▼                ▼                       ▼                    ▼
                     └─ timer ───────────────────── backing off ◄──────────────────────┘

  discovering … backing off ── stop() ──► stopped ── start() ──► new client
  connecting ── cleartext blocked ──► stopped
```

### States and exits

| State | Doing | Exits |
| --- | --- | --- |
| **idle** | Nothing; no client yet. Events are dropped. | `start()` with `enabled` → discovering. `start()` with `enabled = false` → stays idle. |
| **discovering** | A manual `host` is the only source. Otherwise mDNS `_appmonitor._tcp`, then `10.0.2.2` when running on the emulator (ADR-0002), as in [Discovery](#discovery). | An endpoint found → connecting. No source answers within 5 s → backing off. `stop()` → stopped. |
| **connecting** | Opens `ws://host:port/v1/ws`, bounded by 10 s. | Upgraded → handshaking. Refused, unreachable, timed out or not a WebSocket → backing off; the first such failure of a client logs one line naming the endpoint and `ACCESS_LOCAL_NETWORK` (DEBT-0030 paid). Cleartext blocked by the app's network security policy — asked of `NetworkSecurityPolicy` for the host before opening, since CIO's raw sockets ignore it (ADR-0023) → one log line naming the `debug-overrides` fix (ADR-0017), then stopped. `stop()` → stopped. |
| **handshaking** | Sends `Hello` as the first frame and waits for `HelloAck` without a timeout: the desktop may be showing the approval dialog (ADR-0018). The pinger runs, so a dead desktop is still noticed. | `HelloAck` → connected. Closed 1008 (rejected) → backing off at the longest delay. Any other close, ping timeout or socket error → backing off. `stop()` → stopped. |
| **connected** | Sends the buffer in order, then each new event as it arrives. Envelopes on a channel missing from `HelloAck.channels` are dropped, not sent. | Close from the desktop (1000/1001 it stopped, 1002/1009 the SDK broke a rule, 1011 missed pong), ping timeout or socket error → backing off. `stop()` → stopped. |
| **backing off** | Waits 1 s, then 2, 4… up to 30 s, ±20 % jitter. The delay resets to 1 s once a session reaches connected. | Timer → discovering (the desktop may have moved). `stop()` → stopped. |
| **stopped** | Nothing; every resource released, buffer discarded. Events are dropped. | `start()` → a new client in discovering, empty buffer. |

`start()` while a client is running is ignored: the first options stay.

### Discovery

| Source | Used when | Gives up |
| --- | --- | --- |
| Manual `host` (`AppMonitorOptions.host`, `port`) | `host` set; then nothing else is tried | Never: the endpoint is returned at once |
| mDNS (`NsdManager` on Android) | No `host`, and local network access allowed: below Android 17, or `ACCESS_LOCAL_NETWORK` granted, whatever the app targets (an Android 17 emulator gave no implicit grant to a target-36 app, ADR-0030). Not allowed → skipped, one log line per client (ADR-0017 E). A browser that fails → logged, nothing found | On the emulator after 3 s; on a device at the 5 s discovery bound |
| `10.0.2.2:47474` | On the emulator (`Build.HARDWARE` `ranchu` or `goldfish`) and mDNS found nothing | Never |

Every client reads mDNS answers through `protocol` `DesktopSelector`
(ADR-0024), the fake client included:

- The first desktop answered with an IPv4 address is kept for the round;
  answers for any other instance name are ignored. Names compare
  case-insensitively; a `--name` filter (fake client) applies first.
- Its path is the TXT key `path`. An answer without TXT waits up to 500 ms
  for a later one of the same name, then uses `/v1/ws`.
- Resolves never block the browser's listener: JmDNS resolves run on their
  own coroutine; `NsdManager` uses `registerServiceInfoCallback` from API
  34 and one `resolveService` at a time below it.

The device id is read or created in `noBackupFilesDir` on the client's
scope, not on the thread that called `start()`.

### Resources and their owners

| Resource | Owner | Lives |
| --- | --- | --- |
| Coroutine scope (one `SupervisorJob`) | client | `start()` → `stop()`; every resource below runs in it |
| Ktor `HttpClient` (`WebSockets` plugin, ping every 15 s, `maxFrameSize` `Protocol.MAX_FRAME_BYTES`) | client | `start()` → `stop()` |
| Ring buffer | client | `start()` → `stop()`; survives reconnects |
| Device id, `Hello` | client | built once, on the client's scope, at the first handshake |
| Platform resolver (`NsdManager` listeners and resolves on Android) | discovering | released on every exit of discovering |
| WebSocket session | connecting | handed to handshaking, then connected; closed on any exit to backing off or stopped |
| Backoff timer | backing off | cancelled on exit |

No resource outlives the state that owns it; each exit releases before the
next state starts.

### `stop()` in each state

`stop()` returns at once; the release runs on the client's scope and is
bounded by 1 s. Tests await it through an internal join.

| State | What `stop()` releases |
| --- | --- |
| idle, stopped | Nothing. |
| discovering | Stops mDNS browsing and pending resolves. |
| connecting | Cancels the connect; the half-open socket is closed. |
| handshaking, connected | Sends close 1000 and waits up to 1 s for the desktop's; then cancels the session. Buffered events are not flushed. |
| backing off | Cancels the timer. |

In every state it then closes the `HttpClient`, discards the buffer and
cancels the scope.

### Buffer policy

- Events are accepted from any thread in every state from discovering to
  backing off. Enqueueing never blocks and never waits for the network, so
  capture never delays the app's call.
- The buffer holds at most 4096 envelopes and 8 MiB of encoded envelopes;
  when either is reached the oldest whole envelope is dropped.
- An envelope larger than `Protocol.MAX_FRAME_BYTES` is dropped before it
  enters the buffer: the desktop would close 1009 on it (ADR-0021).
- An envelope leaves the buffer when it is written to the socket (at most
  once). It is not resent after a reconnect, so one the desktop rejected
  cannot end every later session.
- Memory only: nothing captured is written to the app's storage.

## Limits

- Dropped envelopes (full buffer, oversized, in flight when the socket
  died) are invisible on the desktop (DEBT-0023).
- An oversized envelope is lost rather than cut where it is built; bodies
  fit since `maxBodyBytes` is capped (ADR-0025), so only URL and headers
  over 256 KiB get there.
- The device connects to any desktop that advertises the service
  (DEBT-0020).
- `AppMonitorOptions.appName` is not sent: `Hello` has no field for it
  (DEBT-0024).
- `NsdBrowser`, the permission check and the emulator test run only on
  Android; host tests use fake browsers (ADR-0024).
