# 04 — SDK connection lifecycle

How `sdk/core` (KMP: Android, JVM and iOS, see
[Kotlin/Native (iOS)](#kotlinnative-ios)) finds the desktop, connects, holds
events while it cannot send them, and lets go of everything on `stop()`.
The transport is `internal` `MonitorClient` in `sdk/core` (ADR-0023), which
connects to the endpoint its `discover` function returns; discovery and the
Android `AppMonitor.start` / `stop` entry point are ADR-0024; on iOS,
ADR-0062 B.1 and ADR-0068. Decisions in
ADR-0022; public API in ADR-0017; approval and masking in ADR-0018. The
Swift SDK runs the same states; its differences are SDD 06.

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
```

### States and exits

| State | Doing | Exits |
| --- | --- | --- |
| **idle** | Nothing; no client yet. Events are dropped. | `start()` with `enabled` → discovering. `start()` with `enabled = false` → stays idle. |
| **discovering** | A manual `host` is the only source. Otherwise mDNS `_appmonitor._tcp`, then `10.0.2.2` when running on the emulator (ADR-0002), as in [Discovery](#discovery). | An endpoint found → connecting. No source answers within 5 s → backing off. `stop()` → stopped. |
| **connecting** | Opens `ws://host:port/v1/ws`, bounded by 10 s. | Upgraded → handshaking. Refused, unreachable, timed out or not a WebSocket → backing off; the first such failure of a client, and the first after each connected session, logs one line naming the endpoint and `ACCESS_LOCAL_NETWORK` (DEBT-0030 paid). The app's network security policy is not consulted: CIO's raw sockets ignore it, and it governs the app's traffic, not the SDK's (ADR-0156). `stop()` → stopped. |
| **handshaking** | Sends `Hello` as the first frame and waits for `HelloAck` without a timeout: the desktop may be showing the approval dialog (ADR-0018). The pinger runs, so a dead desktop is still noticed. | `HelloAck` → connected. Closed 1008 (rejected) → backing off at the longest delay. Any other close, ping timeout or socket error → backing off. `stop()` → stopped. |
| **connected** | Sends the buffer in order, then each new event as it arrives. Envelopes on a channel missing from `HelloAck.channels` are dropped, not sent. Reads the desktop's frames: a `MockRules` on `interaction` replaces the session's rules, a `BreakpointRules` its breakpoints, a `BreakpointResume` or `BreakpointAbort` lets a held call go, a `ReplayRequest` sends a kept request again, anything else is ignored ([Mock rules](#mock-rules), [Breakpoints](#breakpoints), [Replays](#replays)). | Close from the desktop (1000/1001 it stopped, 1002/1009 the SDK broke a rule, 1011 missed pong), ping timeout or socket error → backing off; Ktor's WebSocket call then gets 1 s to finish and is cancelled, since a desktop that stopped answering never closes its side (ADR-0067 D). `stop()` → stopped. |
| **backing off** | Waits 1 s, then 2, 4… up to 30 s, ±20 % jitter. The delay resets to 1 s once a session reaches connected. | Timer → discovering (the desktop may have moved). `stop()` → stopped. |
| **stopped** | Nothing; every resource released, buffer discarded. Events are dropped. | `start()` → a new client in discovering, empty buffer. |

`start()` while a client is running is ignored: the first options stay.

### Mock rules

- Android and iOS list `core`, `network` and `interaction` in `Hello`
  (ADR-0115 A.1).
- After `HelloAck`, each `MockRules` envelope on an accepted
  `interaction` channel replaces `MonitorClient.mocks` whole; one on a
  channel the desktop did not accept, or one that does not decode, is
  ignored. A rule with neither a response nor a failure is left out.
- The rules are emptied when the session ends, by the desktop, the
  network or `stop()`: no mock outlives its desktop (ADR-0110 A.1).
- `MonitorBridge.mockFor(method, url)` returns the first rule matching
  (ADR-0110 B.1): `method` exact or empty, the URL's host ignoring case,
  its path as written (`/` when empty) segment by segment with `*` for
  one segment, query, fragment, port and user info ignored. The URL is
  split by RFC 3986 appendix B (`Mocks.kt`). Pinned by
  `protocol/fixtures/mock_matching.json` (`MockMatchingVectorTest`).
- Tests: `MonitorClientMocksTest` against `StandInDesktop` (replaced,
  emptied by an empty set and by a lost desktop, ignored when not
  accepted or malformed).

### Breakpoints

- Each `BreakpointRules` on an accepted `interaction` channel replaces
  `MonitorClient.breakpoints` whole (ADR-0118 A.1); a rule holding
  neither stage or with no timeout is left out. Emptied when the session
  ends, as the mock rules are.
- `MonitorBridge.breakpointFor(method, url, stage)` returns the first
  rule holding that stage that matches as a mock rule does (`Mocks.kt`'s
  `ruleMatches`).
- `MonitorBridge.hold(hit)` sends the module's `BreakpointHit` on
  `interaction` and suspends until a `BreakpointResume` or
  `BreakpointAbort` of the same request id and stage, or the hit's
  `timeout_ms`, or the session's end: then `BreakpointOutcome.UNCHANGED`
  (ADR-0111 B.1, ADR-0123 A.1). Outside a session it returns at once,
  unchanged. A resume or abort for a call no longer held is dropped.
- `BreakpointOutcome.Resume.headers(list)` applies `set_headers` and
  `removed_headers` by name, ignoring case; a header in neither keeps
  the device's value (ADR-0111 C.1).
- Tests: `MonitorClientBreakpointsTest` against `StandInDesktop` (first
  rule holding the stage, resumed by its own id and stage only, aborted,
  timed out, let go and cleared when the session ends, headers set and
  removed).

### Replays

- A capture module hands each request it captures to
  `MonitorBridge.keepForReplay(id, replay)`: a closure that sends the
  call again, marked `replay_of`, and returns null once it is on its
  way, else the reason (ADR-0122 A.1). Nothing is kept while no monitor
  runs.
- `MonitorClient.replays` (`RecentRequests`) keeps the last 200 by
  request id, a kept id moved last, and is emptied when the session ends,
  as the mock rules are.
- A `ReplayRequest` on an accepted `interaction` channel runs the
  request's closure on the receive loop. An id not kept, a closure's
  reason or its exception becomes a `ReplayRefused` on `interaction`
  (ADR-0120 A.1).
- Tests: `MonitorClientReplayTest` against `StandInDesktop` (sent again,
  refused when unknown, with a module's reason or its exception,
  forgotten when the session ends, only the last kept).

### Discovery

| Source | Used when | Gives up |
| --- | --- | --- |
| Manual `host` (`AppMonitorOptions.host`, `port`) | `host` set; then nothing else is tried | Never: the endpoint is returned at once |
| mDNS (`NsdManager` on Android, `nw_browser` and `dns_sd` on iOS, see [Kotlin/Native (iOS)](#kotlinnative-ios)) | No `host`, and local network access allowed: below Android 17, or `ACCESS_LOCAL_NETWORK` granted, whatever the app targets (an Android 17 emulator gave no implicit grant to a target-36 app, ADR-0030). Not allowed → skipped, one log line per client. `AppMonitor.start` asks for it on the first Activity that stays resumed 500 ms, once per process (ADR-0156); with several resumed at once (multi-window), on the last one resumed, and when that one pauses, on the one left. The first Activity resumed after the dialog logs `ACCESS_LOCAL_NETWORK denied` once when it is still not granted (ADR-0162). A browser that fails → logged, nothing found | On the emulator or simulator after 3 s; on a device at the 5 s discovery bound |
| `10.0.2.2:47474` | On the emulator (`Build.HARDWARE` `ranchu` or `goldfish`) and mDNS found nothing | Never |
| `127.0.0.1:47474` | On the iOS simulator (`iosSimulatorArm64`) and mDNS found nothing | Never |

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

The device id is read or created in `noBackupFilesDir` (Android) or
`Application Support` (iOS) on the client's scope, not on the thread that
called `start()`.

### Resources and their owners

| Resource | Owner | Lives |
| --- | --- | --- |
| Coroutine scope (one `SupervisorJob`) | client | `start()` → `stop()`; every resource below runs in it |
| Ktor `HttpClient` (`WebSockets` plugin, ping every 15 s, `maxFrameSize` `Protocol.MAX_FRAME_BYTES`) | client | `start()` → `stop()` |
| Ring buffer | client | `start()` → `stop()`; survives reconnects |
| Device id, `Hello` | client | built once, on the client's scope, at the first handshake |
| Platform resolver (`NsdManager` listeners and resolves on Android; `nw_browser` and `dns_sd` resolves on iOS) | discovering | released on every exit of discovering |
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
| connecting | Lets the connect end itself: upgraded → closed 1000 as below; failed → stopped. Still connecting at the 1 s bound → cancelled; the half-open socket is closed (ADR-0067 C, DEBT-0053 on iOS). |
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
  once). After each frame the writer calls the session's `flush()`, which
  suspends until every frame sent before it is written to the connection.
  It does not mean the desktop received them. So at most one frame waits
  in Ktor, and the socket's own buffers hold the rest. When the desktop
  stops reading (its store full, ADR-0009), that write waits on TCP and
  new events pile up in this buffer, which drops the oldest
  (`StalledDesktopBreakpointTest`, against a raw socket that stops
  reading). The flush is cancelled with the session, so `stop()` still
  ends within `closeTimeout`. It is not resent after a reconnect, so one the desktop rejected
  cannot end every later session.
- Memory only: nothing captured is written to the app's storage.

### Kotlin/Native (iOS)

Built by PLAN-0005 M4.3 and M4.4 (ADR-0066, ADR-0067, ADR-0068).

`sdk/core` on `iosArm64` and `iosSimulatorArm64` (ADR-0062 E.1) runs the
same `MonitorClient`, states, bounds, backoff and buffer as above. The app
calls `AppMonitor.start(options)` / `stop()` from `iosMain` Kotlin or from
Swift through its framework (ADR-0062 B.1); both return at once and never
throw. Only these differ from the JVM:

| Topic | JVM / Android | iOS |
| --- | --- | --- |
| Ktor engine | CIO | CIO: raw BSD sockets, so the Swift SDK's `URLSession` hooks never see the monitor's own WebSocket, and ATS does not govern it (ADR-0066 A) |
| Locks | atomicfu `SynchronizedObject` and `synchronized`, in common code | Same |
| Oversize counter | a `Long` under the client's lock | Same |
| Device id | `kotlin.uuid.Uuid`, `kotlinx.io.files` in `noBackupFilesDir` | Same code; the Swift SDK's file `Application Support/appmonitor-device-id`, lowercase UUID, either SDK may create it, excluded from backup (ADR-0064 B.1); a new id is written beside it and hard linked in, the first writer wins (ADR-0078); where links are refused (always on Android, SELinux) it is renamed in under a lock on `<file>.lock`, re-read first (ADR-0080); on iOS its directory is made by `NSFileManager`, since kotlinx-io's `Path.parent` is not thread-safe there (ADR-0079) |
| `Hello` | `Build.MANUFACTURER` and `MODEL`, `Android` release, package name | As the Swift SDK: model identifier (`SIMULATOR_MODEL_IDENTIFIER` plus " Simulator" on the simulator, `uname` machine on a device), `iOS X.Y[.Z]`, bundle id (process name without one), `CFBundleShortVersionString` |
| `Hello.app_name` | `AppMonitorOptions.appName`, empty when null (ADR-0165) | The same |
| mDNS | `NsdManager` | `nw_browser` with TXT records for names and `path`; each new name resolved by `DNSServiceResolve` then `DNSServiceGetAddrInfo` (IPv4), no probe connection (ADR-0068). A browser that fails, or waits on `PolicyDenied`, logs once per client and finds nothing |
| Timestamps | `kotlin.time.Clock.System` | Same |
| Socket errors | `kotlinx.io.IOException` | Same |
| `platformLog` | `Log.w` / stderr | `NSLog` |
| Connect failure hint | `ACCESS_LOCAL_NETWORK` (Android) | `NSLocalNetworkUsageDescription` and `NSBonjourServices` (ADR-0063 B.1) |
| `stop()` while connecting | Lets the connect end itself, 1 s bound (ADR-0067 C) | Same; cancelling a connect may leak its socket (DEBT-0053) |
| Threads | `Dispatchers.Default` | `Dispatchers.Default`, the native worker pool; nothing runs on the main thread, so `start()` from the app's main thread does no I/O |
| Emulator / simulator host | `10.0.2.2` when `Build.HARDWARE` says emulator | `127.0.0.1` on `iosSimulatorArm64`, known at compile time (ADR-0002) |

#### States and their iOS exits

Tests run on `iosSimulatorArm64Test` unless marked *JVM*; `commonTest`
ones (`MonitorClientTest`, `MonitorHolderTest`) also run on the JVM and the
Android host, against `StandInDesktop` on `ktor-server-cio` (ADR-0066 C);
`iosTest` ones (`PlatformIosTest`, `IosDeviceTest`, `IosDiscoveryTest`,
`BonjourBrowserTest`) on the simulator only.

| State | iOS exits, where they differ | Test |
| --- | --- | --- |
| **idle** | As above. | `disabledStaysIdle`, `stopWhenIdleDoesNothing` |
| **discovering** | Manual `host` only, else Bonjour (`nw_browser`, `dns_sd`), then `127.0.0.1:47474` on the simulator. | `manualHostIsTheOnlySource`, `stopWhileDiscoveringReleasesEverything`, `simulatorHostFollowsABonjourThatFindsNothing`, `findsAnAdvertisedDesktopWithItsAddressPortAndPath` (a service the test advertises; needs a multicast-capable interface) |
| **connecting** | Refused, unreachable or 10 s → backing off; the first such failure logs one line naming the endpoint and the Info.plist keys of ADR-0063 B.1, not `ACCESS_LOCAL_NETWORK`. No cleartext exit: `isCleartextPermitted` is `true`. | `anUnreachableDesktopIsLoggedOncePerClient`, `anOutageAfterAConnectionIsLoggedAgain`, `aConnectFailureNamesTheInfoPlistKeys`, `cleartextIsAlwaysPermitted` |
| **handshaking** | As above; a 1008 stays rejected when the call's teardown then throws. | `aRejectedDeviceWaitsTheLongestDelay`, `aRejectionWhoseTeardownThrowsStillWaitsTheLongestDelay` |
| **connected** | As above; a write to a socket the desktop closed ends in backing off, no `SIGPIPE` (ADR-0067). | `handshakesWithTheDesktop`, `delivers1000EventsInOrder`, `aDesktopThatDiesMidSessionEndsInBackingOff`; *JVM*: `dropsEnvelopesOnChannelsTheDesktopDidNotAccept` |
| **backing off** | As above. | `eventsQueuedWhileTheDesktopIsDownArriveAfterItReturns` |
| **stopped** | As above. | `eventsAfterStopAreDropped`, `anEnvelopeLargerThanAFrameIsDroppedBeforeTheBuffer` |

`start()` while running and `start()` after `stop()`:
`aSecondStartIsIgnoredWhileRunning`, `startAfterStopBuildsANewClient`.
Device id (`DeviceIdTest`): `isStableAcrossStarts`, `aCorruptFileIsReplaced`;
(`IosDeviceTest`) `theDeviceIdFileIsTheSwiftSdks`, `readsTheSwiftSdkDeviceIdFile`,
`helloNamesTheSimulatorModelAndIos`. Two sessions with one (`device_id`,
`app_id`), as both SDKs in one app open, get one approval and stay open
together: *JVM* `twoSessionsOfOneDeviceAndAppStayOpenTogether` (ADR-0064 B.1).

#### `stop()` on iOS

As in [`stop()` in each state](#stop-in-each-state), with the same tests:
`stopWhileDiscoveringReleasesEverything` (the Bonjour browser instead of
`NsdManager`), `stopWhileConnectingClosesTheHalfOpenSocket`,
`stopWhileHandshakingClosesTheSessionNormally`, `stopWhileBackingOffCancelsTheTimer`,
and `stopWhileConnectedEndsTheDesktopSession` (*JVM*: it reads the real
desktop's session list, `MonitorClientDesktopTest`).

#### Background and foreground

No lifecycle observer, as in the Swift SDK (SDD 06): a suspended app runs
no coroutine, so the client neither sends nor times out meanwhile. On
resume the state goes on from where it was: a connection the OS dropped
ends with a socket error or the ping timeout → backing off → discovering.
Whether a backoff delay counts the time suspended is not specified. Events captured
before suspension stay in the buffer. Test:
`aDesktopThatStopsAnsweringPingsEndsInBackingOff`, a stand-in desktop that
keeps the socket open but never answers, as a suspended or vanished desktop
looks from the app. `LiveServerTest` reaches `make server` from the
simulator when `APPMONITOR_LIVE_HOST` is set (ADR-0067 B), and by Bonjour
when `APPMONITOR_LIVE_DISCOVERY=1`.

## Limits

- Dropped envelopes (full buffer, oversized, in flight when the socket
  died) are invisible on the desktop (DEBT-0023).
- An oversized envelope is lost rather than cut where it is built; bodies
  fit since `maxBodyBytes` is capped (ADR-0025), so only URL and headers
  over 256 KiB get there.
- The device connects to any desktop that advertises the service
  (DEBT-0020).
- `NsdBrowser`, the permission check and request and the emulator test run only on
  Android; host tests use fake browsers (ADR-0024). The iOS
  `BonjourBrowser` runs for real on the simulator.
- On iOS, local network privacy with CIO's BSD sockets is unproven on a
  physical iPhone; the simulator has no prompt (DEBT-0052).
