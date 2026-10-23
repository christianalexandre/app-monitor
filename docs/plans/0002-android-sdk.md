---
id: PLAN-0002
title: Android SDK
status: Active # Draft | Active | Done | Abandoned
milestone: M1
started: 2026-10-22
finished:
---

# PLAN-0002 — Android SDK (M1)

## Goal

A real Android app, on the emulator and on a device, shows its OkHttp traffic
in the desktop after the developer approves the device, with sensitive
headers masked on the device and bodies bounded end to end.

## Tasks

Decisions in the "stop and ask" areas of `AGENTS.md` come first, so the
code that depends on them is written once. M0's lesson: lifecycles are
written down as states and transitions (SDD) before their code.

- [x] **M1.1 SDK API and privacy decisions.** (ADR-0017, ADR-0018 `Accepted`: the owner took every recommendation, `minSdk` 24) Two `Proposed` ADRs, one stop:
  (a) the public API an app developer touches — `AppMonitor.start(...)`
  options (manual host, enabled flag), the OkHttp interceptor, the no-op
  artifact's matching surface, the SDK's own `minSdk`; (b) what is captured
  and masked — default masked headers, body size limit, where masking runs
  (on the device, before the wire), and the device approval flow (when
  `HelloAck` is sent, what is remembered and where until M5).
  Accept: both ADRs merged by the owner with `status: Accepted`.
- [x] **M1.2 Protocol: bodies bounded.** (ADR-0020; an unread body is `body_truncated` with an empty body, no separate marker) `body_truncated` and original body
  size on `RequestStarted` / `ResponseReceived` (new field numbers, none
  renumbered); fixtures regenerated; the desktop shows "truncated, N bytes".
  Accept: `./gradlew :protocol:allTests :desktop:app:test` — fixture and
  round-trip tests for the new fields; a UI test shows the truncation note.
- [x] **M1.3 Server hardening for real devices.** (ADR-0021; DEBT-0008 and DEBT-0018 paid by closing with 1002; DEBT-0021, DEBT-0022 opened) WebSocket `maxFrameSize`
  derived from the body limit; keep-alive ping with a timeout that ends the
  session of a device gone from Wi-Fi; accepted channels enforced
  (DEBT-0008); envelopes without a `request_id` rejected or kept apart
  (DEBT-0018). The 10 s `Hello` timeout already exists (M0.R6).
  Accept: `./gradlew :desktop:server:test :desktop:app:test` — an oversized
  frame closes with 1009; a client that stops answering pings ends its
  session within the bound; a non-accepted channel and an empty
  `request_id` follow the documented rule.
- [x] **M1.4 SDK connection lifecycle on paper.** (`docs/sdk-connection-lifecycle`; SDD 04, ADR-0022, DEBT-0023) SDD chapter for `sdk/core`:
  states (idle, discovering, connecting, handshaking, connected, backing
  off, stopped), transitions, who owns each resource, what `stop()` during
  each state does, buffer policy when offline.
  Accept: `make docs-check`; the chapter lists every state with its exits.
- [x] **M1.5 `sdk/core` transport.** (ADR-0023; `internal` `MonitorClient` takes a `discover` function; the `AppMonitor` entry point moved to M1.6, which needs the `Context`) KMP module (Android + JVM): Ktor client
  WebSocket, `Hello`/`HelloAck`, an in-memory ring buffer that drops the
  oldest events when full, reconnection with backoff, all per M1.4. Connects
  to an explicit host only.
  Accept: `./gradlew :sdk:core:allTests` — against an in-process
  `AppMonitorServer`: handshake, 1000 events delivered in order, events
  queued while the desktop is down arrive after it returns, `stop()` in each
  state releases everything.
- [x] **M1.6 `sdk/core` discovery.** (ADR-0024; `DesktopSelector` in `protocol`, shared with the fake client; DEBT-0009, DEBT-0011 paid; DEBT-0005, DEBT-0012 re-armed; DEBT-0024 opened; masking options left to M1.7) `AppMonitor.start(context, options)` /
  `stop()` per SDD 04 (idle, ignored second start, new client after stop). Manual host → mDNS (`NsdManager` on
  Android) → `10.0.2.2` on the emulator (ADR-0002), with a shared discovery
  contract that pays DEBT-0005, DEBT-0009, DEBT-0011 and DEBT-0012 (or
  re-arms each with a reason).
  Accept: `./gradlew :sdk:core:allTests` — order of fallbacks with fake
  resolvers; a late TXT record and two services with different names are
  handled as documented.
- [x] **M1.7 `sdk/android-okhttp` interceptor.** (ADR-0025, SDD 05; masking and `maxBodyBytes` added to `AppMonitorOptions`, capped at `Protocol.MAX_BODY_BYTES`; DEBT-0021 paid, DEBT-0025 opened; also captures WebSocket frames through `AppMonitorWebSocketFactory`; Accept ran as `:sdk:android-okhttp:allTests`, tests are in `jvmTest`) Captures request, response,
  failure and WebSocket frames from OkHttp, applies M1.1's masking and body
  limit before handing events to `sdk/core`; never alters or delays the
  app's call beyond reading the bounded body.
  Accept: `./gradlew :sdk:android-okhttp:testDebugUnitTest` (or the module's
  host test task) — with OkHttp `MockWebServer`: events for 2xx, 5xx, a
  failure, a body over the limit, a masked header, a streamed body the app
  still reads whole.
- [x] **M1.8 `sdk/no-op`.** (ADR-0026; one artifact for `sdk/core` + `sdk/android-okhttp`; the check compares the Kotlin plugin's committed ABI dumps, `checkKotlinAbi` now in every SDK module's `check`; `AppMonitorInterceptor` constructors reshaped for the dumper, DEBT-0026) Same public API as M1.1, no code that touches the
  network; a check that its API matches the real SDK's.
  Accept: `./gradlew :sdk:no-op:check` — the API comparison passes and fails
  when one side gains a public member.
- [x] **M1.9 Desktop device approval.** (ADR-0027; a `DeviceApproval` hook on `AppMonitorServer`, `DeviceApprovals` in memory, an in-window panel; a frame before `HelloAck` closes 1002; DEBT-0028 opened, DEBT-0024 not triggered: the dialog shows `app_id`) Approval dialog per M1.1: a new device
  waits; approve or reject; approved devices stay approved for the app's
  lifetime (persisted in M5, DEBT if needed).
  Accept: `./gradlew :desktop:server:test :desktop:app:test` — a pending
  device gets no `HelloAck` until approved; a rejected one is closed; a UI
  test drives the dialog.
- [x] **M1.10 WebSocket frames and bounded bodies in the UI.** (ADR-0028, ADR-0029; DEBT-0003, DEBT-0006 paid; DEBT-0025 paid too, its trigger was this task: `WebSocketFrame` gained `payload_truncated` / `payload_size`; pings split to DEBT-0029) Pays DEBT-0003
  (frames shown under their exchange) and DEBT-0006 (rendering off the UI
  thread, capped).
  Accept: `./gradlew :desktop:app:test` — a UI test shows frames of a
  WebSocket exchange; a body at the protocol limit renders without blocking
  the UI thread (bounded time).
- [x] **M1.11 Android sample app.** (ADR-0030; Compose BOM held at 1.11 for `compileSdk` 36; the Android 17 emulator gave no implicit local network grant, so the debug sample asks for it, DEBT-0030; `make server` / `make fake-traffic` failed without `ARGS` on Gradle 9.7, fixed) `samples/android`: a Compose app with a
  few buttons (GET JSON, POST, 404, timeout, WebSocket echo) using OkHttp
  with the interceptor; debug uses `sdk/android-okhttp`, release `sdk/no-op`.
  Accept: `./gradlew :samples:android:assembleDebug :samples:android:assembleRelease`;
  on the `Medium_Phone` emulator with `make server` running, each button
  shows up in the server log (log in the PR); release APK contains no
  `sdk/core` classes.
- [ ] **M1.12 End to end on emulator and device.** Sample app → desktop app
  with approval, on the emulator (`10.0.2.2`) and on a physical device (mDNS).
  Decide whether the emulator run becomes a `make` target now or in M3
  (DEBT-0002's trigger).
  Accept: screenshots in `docs/screenshots/` of the desktop showing the
  sample's traffic from each; if no physical device is available, stop and
  say so.
- [ ] **M1.13 Docs sync.** SDD architecture and code map, `AGENTS.md`
  commands, README status rows reflect M1.
  Accept: `make docs-check`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
