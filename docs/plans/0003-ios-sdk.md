---
id: PLAN-0003
title: iOS SDK
status: Active # Draft | Active | Done | Abandoned
milestone: M2
started: 2026-10-23
finished:
---

# PLAN-0003 — iOS SDK (M2)

## Goal

A real iOS app, on the simulator and on a device, shows all its `URLSession`
traffic (ADR-0004 layer 1) in the desktop after the developer approves the
device, through a native Swift package that speaks the same protocol as the
Kotlin SDKs, proven by the shared fixtures.

## Tasks

M1's lessons applied: decisions in the "stop and ask" areas of `AGENTS.md`
come first, with the owner asked which side the masking defaults err on
(ADR-0034); every place a secret can sit is listed before the capture code;
the Swift transport follows SDD 04's lifecycle, written down for Swift before
its code. The previous tool's source is
`github.com/christianalexandre/app-network-monitor` (public), ported without
Pulse (ADR-0003).

- [!] **M2.1 iOS SDK API, privacy and CI decisions.** (`docs/ios-sdk-decisions`; ADR-0035, ADR-0036, ADR-0037 `Proposed`, waiting for the owner) `Proposed` ADRs, one
  stop: (a) the public API an app developer touches — `AppMonitor.start(...)`
  and its options (mirroring ADR-0017 where Swift allows), how capture is
  turned on (automatic `URLSessionConfiguration` coverage vs an explicit
  call), the SwiftPM products (SDK and no-op), the minimum iOS version;
  (b) what is captured and masked — every place a secret can sit in a
  `URLSession` exchange (headers, URL user info, query, fragment, URL
  response headers, WebSocket frames, bodies), the defaults, the `masking`
  switch (ADR-0034), the Info.plist keys the SDK requires; (c) where Swift
  tests run: PR CI on a macOS runner (10× minutes, ADR-0012) or locally plus
  M3's release gate.
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [ ] **M2.2 Protocol: capture layer.** A field telling which capture layer
  produced an entry (ADR-0004 requires the UI to show it), new field numbers,
  none renumbered; Kotlin SDKs set it; fixtures regenerated; the desktop
  shows it.
  Accept: `./gradlew :protocol:allTests :desktop:app:test` — fixture and
  round-trip tests for the field; a UI test shows the layer.
- [ ] **M2.3 Swift package and contract tests.** `sdk/ios` SwiftPM package,
  swift-protobuf code generated from `protocol/proto`, a `make` target for
  the Swift gates wired as M2.1 (c) decides.
  Accept: `swift test --package-path sdk/ios` — every file in
  `protocol/fixtures/` decodes to the expected values and re-encodes byte for
  byte.
- [ ] **M2.4 Swift connection lifecycle on paper.** SDD 04 (or a new
  chapter) states where the Swift transport differs from `sdk/core`:
  Network.framework or `URLSessionWebSocketTask`, threading (actors, queues),
  who owns each resource, what `stop()` does in each state.
  Accept: `make docs-check`; every SDD 04 state has its Swift exits.
- [ ] **M2.5 Swift transport.** Handshake, ring buffer that drops the oldest,
  reconnection with backoff, all per M2.4. Connects to an explicit host only.
  Accept: `swift test --package-path sdk/ios` — against an in-process
  WebSocket server: handshake, 1000 events in order, events queued while the
  desktop is down arrive after it returns, `stop()` in each state releases
  everything; one run against `make server` logged in the PR.
- [ ] **M2.6 Swift discovery.** Manual host → `NWBrowser` → the simulator's
  host loopback, per the discovery contract (ADR-0024: late TXT, two
  services, selector rules).
  Accept: `swift test --package-path sdk/ios` — order of fallbacks and the
  ADR-0024 cases with fake browsers.
- [ ] **M2.7 `URLSession` capture.** Request, response and failure events
  from every `URLSession`, sessions created before `start()` included,
  uploads (data, file, stream), with M2.1's masking and body limit; never
  alters the app's call beyond reading the bounded body.
  Accept: `swift test --package-path sdk/ios` — against a local HTTP server:
  2xx, 5xx, a failure, a body over the limit, a masked header, a session
  created before `start()`, each upload kind.
- [ ] **M2.8 WebSocket tasks.** Frames of `URLSessionWebSocketTask` sent and
  received, bounded as `WebSocketFrame` (ADR-0028).
  Accept: `swift test --package-path sdk/ios` — text and binary frames both
  ways and a frame over the limit, against a local echo server.
- [ ] **M2.9 Background sessions.** Background `URLSession` traffic through
  delegate proxying (ADR-0004 layer 1).
  Accept: an `xcodebuild test` on the iPhone simulator — a background
  download is captured and the app's delegate still gets every callback.
- [ ] **M2.10 No-op product.** Same public API as M2.1, no network code; a
  check that fails when one side gains a public symbol.
  Accept: the check passes, and fails on a public member added to one side.
- [ ] **M2.11 Info.plist build-phase script.** Adds the local network keys
  M2.1 (b) decides to debug builds only.
  Accept: a debug build of the sample has the keys, a release build has
  none (`plutil` output in the PR).
- [ ] **M2.12 SwiftUI sample app.** `samples/ios`: GET JSON, POST, 404,
  timeout, upload, WebSocket echo, background download; debug uses the SDK,
  release the no-op product.
  Accept: `xcodebuild build` for the simulator in both configurations; with
  `make server` running, each button shows up in the server log (log in the
  PR); the release app contains no SDK symbols.
- [ ] **M2.13 End to end on simulator and device.** Sample app → desktop app
  with approval, on the simulator and on a physical iPhone (mDNS).
  Accept: screenshots in `docs/screenshots/` of the desktop showing the
  sample's traffic from each; if no physical device is available, stop and
  say so.
- [ ] **M2.14 Docs sync.** SDD architecture and code map, `AGENTS.md`
  commands, README status rows reflect M2.
  Accept: `make docs-check`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
