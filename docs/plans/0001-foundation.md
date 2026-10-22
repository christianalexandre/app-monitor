---
id: PLAN-0001
title: Foundation
status: Active # Draft | Active | Done | Abandoned
milestone: M0
started: 2026-10-21
finished:
---

# PLAN-0001 — Foundation (M0)

## Goal

A desktop app that receives fake traffic over the real protocol.

## Tasks

- [x] **M0.1 Gradle skeleton.** (#4) Wrapper, version catalog, `build-logic`
  convention plugins (KMP library, JVM, Compose desktop), ktlint,
  `.editorconfig`. `make check` runs docs-check + lint + build.
  Accept: `make check` passes on a clean clone.
  Deviation: KMP convention has no Android target yet; it lands with AGP
  in M0.2 (ADR-0006). Wrapper pinned to Gradle 9.7.1, not 9.8.2 (KGP limit).
- [x] **M0.2 Protocol module.** `protocol/` with `.proto` for `Envelope`,
  `Hello`/`HelloAck`, `network` channel (`RequestStarted`, `ResponseReceived`,
  `RequestFailed`, `WebSocketFrame`). Wire codegen for JVM, Android and iOS
  targets. Golden binary fixtures in `protocol/fixtures/`.
  Accept: `./gradlew :protocol:allTests` — round-trip and fixture tests pass.
  Deviation: Android target added to `appmonitor.kmp-library` with AGP
  9.3.3 (ADR-0007). Fixture files are checked on JVM only (ADR-0008).
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

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`).
