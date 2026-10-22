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
- [x] **M0.2 Protocol module.** (#5) `protocol/` with `.proto` for `Envelope`,
  `Hello`/`HelloAck`, `network` channel (`RequestStarted`, `ResponseReceived`,
  `RequestFailed`, `WebSocketFrame`). Wire codegen for JVM, Android and iOS
  targets. Golden binary fixtures in `protocol/fixtures/`.
  Accept: `./gradlew :protocol:allTests` — round-trip and fixture tests pass.
  Deviation: Android target added to `appmonitor.kmp-library` with AGP
  9.3.3 (ADR-0007). Fixture files are checked on JVM only (ADR-0008).
- [x] **M0.3 Desktop server.** (#6) `desktop/server`: Ktor WebSocket server,
  handshake, per-device sessions exposed as `Flow`, mDNS advertisement
  (`_appmonitor._tcp`) via JmDNS.
  Accept: integration test — a client connects, handshakes, sends 100
  envelopes, server emits 100 events; mDNS service resolves in test.
- [x] **M0.4 Fake client.** (#8) `tools/fake-client` (JVM CLI) that discovers the
  desktop and replays fixture traffic. `make fake-traffic`.
  Accept: running it against the server shows the requests in the server log.
  Deviation: the "server log" needed a headless `main` in `desktop/server`
  (`make server`), added here (ADR-0010).
- [x] **M0.5 Desktop app skeleton.** (#9) `desktop/app` in Compose: device list,
  request list (method, URL, status, duration), detail (headers, body as
  text/JSON/hex). In-memory store.
  Accept: Compose desktop UI test with fake traffic; screenshot in the PR.
  Deviation: WebSocket frames left out (DEBT-0003). DEBT-0002's "first UI
  suite" trigger fired; measured and re-armed instead of paid (UI test 0.65 s).
- [x] **M0.6 PR CI.** (#10) `.github/workflows/ci.yml` on PRs to `dev`: `make check`
  + JVM tests on ubuntu.
  Accept: workflow green on the PR that adds it.
  Deviation: also runs on push to `dev`, so PRs read a shared Gradle cache;
  `setup-gradle` uses the open-source `basic` cache provider (ADR-0012).
- [x] **M0.7 Docs sync.** SDD code map and `AGENTS.md` commands reflect M0.
  Accept: `make docs-check`; checkpoint with the owner.
  Deviation: the owner checkpoint is the close-plan PR, after the milestone
  review. README's Desktop row moved from planned to skeleton.

### Milestone review (M0 checkpoint)

Findings of the `code-review` of `d8fbae9..d6dfdc8`. Two became debts:
DEBT-0004 (quadratic ingest in `TrafficState`) and DEBT-0005 (duplicated
LAN-interface filter).

- [ ] **M0.R1 Loop scripts.** `scripts/loop_watch.sh` dies silently under
  `pipefail` when `.loop/` has no session; the `loop.sh` watchdog trap does not
  exit, so a normal session end still runs `kill -TERM` on a reaped PID and can
  leave a spurious `.timeout`.
  Accept: `make loop-watch` with an empty `.loop/` prints the "no session"
  message; a bash test of `run_session` with a short command leaves no
  `.timeout` and returns the command's exit code.
- [ ] **M0.R2 Fake client handshake failure.** When the first reply is not a
  `HelloAck`, `Replay` awaits the close reason and hangs if the peer keeps the
  socket open; the `IllegalStateException` escapes `Main` as a stack trace.
  Accept: `./gradlew :tools:fake-client:test` — a test server that answers
  with a non-`HelloAck` frame and stays open makes `replay` fail within a
  bounded time with a clear error.
- [ ] **M0.R3 mDNS partial failure.** `MdnsAdvertiser` leaks the JmDNS
  instances already created when a later interface throws, and
  `AppMonitorServer.start` leaves the HTTP server bound with no advertiser.
  Accept: `./gradlew :desktop:server:test` — a test that makes registration
  fail on one address shows every created instance closed and the port free.
- [ ] **M0.R4 Body rendering cost and strictness.** `indentJson` is O(n²)
  (`nextSignificant` copies the rest of the body per bracket); `render` is not
  memoized in `App.kt`; bare words nested in objects/arrays (`{"a": foo}`)
  are accepted as JSON.
  Accept: `./gradlew :desktop:app:test` — tests for a nested bare word
  (not JSON) and for a multi-MB JSON body formatted in linear time.
- [ ] **M0.R5 Comment length.** KDoc blocks over 4 lines in `desktop/server`
  (`DeviceSession.kt`, `AppMonitorServer.kt`, `ServerConfig.kt`) and any other
  source block breaking `docs/guidelines/02_comments.md`.
  Accept: no comment block over 4 lines outside public SDK API (grep in the PR).

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`).
