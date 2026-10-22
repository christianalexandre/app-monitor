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
| [0008](0008-accepted-channels-not-enforced.md) | Accepted channels are not enforced after the handshake | The first channel beyond `core` and `network` enters the protocol, or the M1 SDK sends envelopes | Open |
| [0009](0009-discovery-ignores-late-txt.md) | Fake client discovery keeps the resolve before the TXT record | The advertised path can differ from `Protocol.WEBSOCKET_PATH`, or M1 `sdk/core` implements discovery | Open |
| [0010](0010-headless-server-bind-failure-trace.md) | Headless server prints a stack trace when the port is taken | The headless server is started by something other than a developer at a terminal, or someone reports the trace as a bug | Open |
