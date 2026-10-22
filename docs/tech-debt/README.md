# Tech debt

What is known to be missing, accepted consciously. Every item has a
**trigger**: the event that forces it to be paid.

Rules:

- **Next number**: the highest in this folder + 1, four digits. Copy
  `_template.md` to `NNNN-kebab-slug.md`.
- **No trigger, no debt.** "When there is time" is not a trigger; "before the
  first public release" is.
- Paid or dropped: set `status` and `paid_date`, fill "How it was paid", and
  remove `DEBT-NNNN` references from code in the same PR.
- Check for an equivalent item before creating a new one.
- Add the row to the index below.

## Index

| Debt | Title | Trigger | Status |
| --- | --- | --- | --- |
| [0001](0001-jmdns-pinned-to-3-6-3.md) | JmDNS pinned to 3.6.3 | A JmDNS release newer than 3.6.4, or a security advisory for 3.6.3 | Open |
| [0002](0002-make-check-runs-full-build.md) | make check runs the full Gradle build | `make check` over 3 minutes on a warm cache, or the first emulator/simulator e2e suite lands | Open |
| [0003](0003-websocket-frames-not-shown.md) | Desktop app ignores WebSocket frames | The first SDK that emits `WebSocketFrame` lands | Open |
| [0004](0004-traffic-state-quadratic-ingest.md) | TrafficState ingest is linear per event | M5 replaces the in-memory store, or a session over 10k requests lags the UI | Open |
| [0005](0005-duplicated-lan-interface-filter.md) | LAN interface filter duplicated in server and fake client | The first change to either filter, or M1 `sdk/core` implements discovery | Open |
| [0006](0006-body-rendering-unbounded.md) | Body rendering is synchronous and unbounded | The first SDK that captures real app bodies lands (M1), or a body freezes the detail pane | Open |
| [0007](0007-mdns-failure-stops-server.md) | mDNS failure on one interface stops the whole server | A desktop reports "Server failed" because of one interface, or M1 SDKs connect by explicit host | Open |
| [0008](0008-accepted-channels-not-enforced.md) | Accepted channels are not enforced after the handshake | The first channel beyond `core` and `network` enters the protocol, or the M1 SDK sends envelopes | Paid |
| [0009](0009-discovery-ignores-late-txt.md) | Fake client discovery keeps the resolve before the TXT record | The advertised path can differ from `Protocol.WEBSOCKET_PATH`, or M1 `sdk/core` implements discovery | Open |
| [0010](0010-headless-server-bind-failure-trace.md) | Headless server prints a stack trace when the port is taken | The headless server is started by something other than a developer at a terminal, or someone reports the trace as a bug | Open |
| [0011](0011-fake-client-discovery-listener.md) | Fake client discovery blocks the JmDNS listener and matches names by case | More than one `_appmonitor._tcp` service is seen on one LAN, or M1 `sdk/core` implements discovery | Open |
| [0012](0012-mdns-instances-share-names.md) | Per-address mDNS instances share a host and service name | A desktop with two addresses on one LAN is seen renamed or flapping, or M1 SDK discovery filters by service name | Open |
| [0013](0013-loop-timeout-leaves-children.md) | Loop timeout kills only the session process | An iteration after a timeout fails on a held port or a Gradle lock | Open |
| [0014](0014-late-frame-rejection-reported-normal.md) | A rejection of a late frame reaches the client as a normal close | An SDK or a test needs to know the desktop rejected one of the last frames it sent | Open |
| [0015](0015-stop-bound-vs-jmdns-close.md) | The 5 s stop bound may cut a slow mDNS close | A "Server stop timed out" status or a shutdown hook timeout seen on a real desktop | Open |
| [0016](0016-loop-timeout-needs-exit-143.md) | A watchdog kill that does not exit 143 is not reported as a timeout | A loop session killed by the watchdog is logged as "no verdict line" instead of a timeout | Open |
| [0017](0017-spotless-walks-build-dir.md) | Spotless walks build directories while tests write them | The same spotless failure on a build file appears a second time, locally or in CI | Open |
| [0018](0018-empty-request-id-merges-exchanges.md) | Network events without a request id merge into one exchange | The M1 SDK starts sending network envelopes to the desktop | Paid |
| [0019](0019-sdk-bodies-not-masked.md) | The SDK masks headers and query parameters, never bodies | The first request to mask a field inside a request or response body | Open |
| [0020](0020-device-trusts-any-desktop.md) | A device sends its traffic to any desktop that advertises the service | Before the first public release of an SDK | Open |
| [0021](0021-sdk-frames-over-desktop-limit.md) | An SDK frame over the desktop's limit ends the session | M1.7 exposes `maxBodyBytes` in `sdk/android-okhttp` | Open |
| [0022](0022-stalled-collector-misses-pongs.md) | A stalled collector makes a healthy device miss its ping timeout | Desktop ingest writes to SQLite (M5), or a session closed with 1011 "Ping timeout" while the device was reachable | Open |
| [0023](0023-sdk-event-loss-is-silent.md) | Events the SDK drops are invisible on the desktop | A developer reports an event missing from the desktop, or the protocol gains per-envelope sequence numbers | Open |
