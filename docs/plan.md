# Plan

The queue `/next` consumes. Milestones run in order; tasks inside a milestone
run top to bottom unless marked otherwise.

## How to read and update this file

- Status: `[ ]` todo · `[~]` in progress (`branch`) · `[x]` done (`#PR`) ·
  `[!]` blocked (reason, ADR or DEBT id).
- Every task has an **Accept** line: a command or observable result that proves
  it. A task is not `[x]` until that proof is in its PR.
- Only the **current milestone** is broken into small tasks. The first task of
  every later milestone is "refine": break it down here, in a `docs/` PR,
  before writing code.
- Tasks may be split, reordered or added inside a milestone by the agent.
  Adding or dropping a **feature** is product scope: ask the owner.
- A milestone ends with a checkpoint: the loop stops and the owner reviews.

---

## M0 — Foundation

Goal: a desktop app that receives fake traffic over the real protocol.

- [ ] **M0.1 Gradle skeleton.** Wrapper, version catalog, `build-logic`
  convention plugins (KMP library, JVM, Compose desktop), ktlint,
  `.editorconfig`. `make check` runs docs-check + lint + build.
  Accept: `make check` passes on a clean clone.
- [ ] **M0.2 Protocol module.** `protocol/` with `.proto` for `Envelope`,
  `Hello`/`HelloAck`, `network` channel (`RequestStarted`, `ResponseReceived`,
  `RequestFailed`, `WebSocketFrame`). Wire codegen for JVM, Android and iOS
  targets. Golden binary fixtures in `protocol/fixtures/`.
  Accept: `./gradlew :protocol:allTests` — round-trip and fixture tests pass.
- [ ] **M0.3 Desktop server.** `desktop/server`: Ktor WebSocket server,
  handshake, per-device sessions exposed as `Flow`, mDNS advertisement
  (`_appmonitor._tcp`) via JmDNS.
  Accept: integration test — a client connects, handshakes, sends 100
  envelopes, server emits 100 events; mDNS service resolves in test.
- [ ] **M0.4 Fake client.** `tools/fake-client` (JVM CLI) that discovers the
  desktop and replays fixture traffic. `make fake-traffic`.
  Accept: running it against the server shows the requests in the server log.
- [ ] **M0.5 Desktop app skeleton.** `desktop/app` in Compose: device list,
  request list (method, URL, status, duration), detail (headers, body as
  text/JSON/hex). In-memory store.
  Accept: Compose desktop UI test with fake traffic; screenshot in the PR.
- [ ] **M0.6 PR CI.** `.github/workflows/ci.yml` on PRs to `dev`: `make check`
  + JVM tests on ubuntu.
  Accept: workflow green on the PR that adds it.
- [ ] **M0.7 Docs sync.** SDD code map and `AGENTS.md` commands reflect M0.
  Accept: `make docs-check`; checkpoint with the owner.

## M1 — Android SDK

Goal: a real Android app (emulator and device) shows traffic in the desktop.

- [ ] **M1.0 Refine** this milestone into tasks.
- Scope: `sdk/core` (KMP: discovery mDNS → `10.0.2.2`, connection, offline
  ring buffer, reconnection), `sdk/android-okhttp` interceptor, `sdk/no-op`,
  Android sample app, device approval dialog in the desktop, header masking.

## M2 — iOS SDK (capture layer 1)

Goal: a real iOS app (simulator and device) shows all its URLSession traffic.

- [ ] **M2.0 Refine** this milestone into tasks.
- Scope: Swift package `sdk/ios` (Network.framework transport, NWBrowser,
  swift-protobuf), URLSession capture ported from `app-network-monitor`
  without Pulse, sessions created before `start()`, uploads, WebSocket tasks,
  background sessions (ADR-0004 layer 1). Contract tests against
  `protocol/fixtures/`. SwiftUI sample app. Info.plist build-phase script.

## M3 — Release gate

Goal: nothing reaches `main` without proving the whole system still works.

- [ ] **M3.0 Refine** this milestone into tasks.
- Scope: `.github/workflows/release-gate.yml` on PRs to `main` (only from
  `dev`):
  - desktop build and tests on ubuntu, macOS and windows;
  - protocol contract tests Kotlin ↔ Swift;
  - Android e2e: sample app on an emulator talking to a headless desktop
    server;
  - iOS e2e: sample app on a simulator talking to a headless desktop server;
  - packaging dry-run (`.dmg`, `.msi`, `.deb`) and SDK publication dry-run.
- Branch protection/rulesets on `main` requiring the gate, if the plan allows
  (otherwise a DEBT with trigger).
- macOS runners cost 10× minutes on private repos: iOS and macOS jobs run only
  in the release gate, not on every PR.

## M4 — KMP SDK

- [ ] **M4.0 Refine.** Scope: `sdk/ktor` HttpClient plugin (all targets),
  correlation header and dedupe with the Swift SDK (ADR-0003), KMP sample app.

## M5 — Persistence and runs

- [ ] **M5.0 Refine.** Scope: `desktop/storage` with SQLDelight (devices,
  apps, runs, requests, bodies compressed), named runs, search and filters,
  retention settings.

## M6 — MCP server

- [ ] **M6.0 Refine.** Scope: `desktop/mcp` on `127.0.0.1` (ADR-0005), tools
  `list_runs`, `search_requests`, `get_request`, `endpoint_stats`,
  `diff_runs`, `export_har`, `to_curl`; setup docs for Claude Code.

## M7 — Issues, benchmarks, export

- [ ] **M7.0 Refine.** Scope: issues (requests + note + tags + status, also via
  MCP `save_issue`/`list_issues`), per-endpoint p50/p95/p99, error rate, bytes,
  run-to-run diff, HAR and cURL export in the UI.

## M8 — iOS deep capture (layers 2–4)

- [ ] **M8.0 Refine.** Scope: grpc-swift interceptor adapter, connection
  metadata via symbol rebinding, desktop MITM proxy mode with HTTP/2 + gRPC
  decoding and simulator CA install (ADR-0004). First, identify with
  Instruments which SDK the old tool missed.

## M9 — Interaction

- [ ] **M9.0 Refine.** Scope: mocks and breakpoints (desktop → device),
  replay, Gradle plugin that injects the OkHttp interceptor automatically.

## M10 — Distribution

- [ ] **M10.0 Refine.** Scope: signed desktop packages via GitHub Releases,
  Homebrew cask, Maven Central, SwiftPM tags, docs site, README with GIFs.

## Later — more debug channels

Logs, analytics events, app storage (DB, preferences), deeplinks, performance.
Each one is a channel (ADR-0001) and a `desktop/feature-*` module. Product
scope: the owner picks the order.
