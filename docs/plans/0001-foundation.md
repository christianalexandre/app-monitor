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

- [x] **M0.R1 Loop scripts.** `scripts/loop_watch.sh` dies silently under
  `pipefail` when `.loop/` has no session; the `loop.sh` watchdog trap does not
  exit, so a normal session end still runs `kill -TERM` on a reaped PID and can
  leave a spurious `.timeout`.
  Accept: `make loop-watch` with an empty `.loop/` prints the "no session"
  message; a bash test of `run_session` with a short command leaves no
  `.timeout` and returns the command's exit code.
  Deviation: `run_session` takes its command and the watchdog became its own
  function, so `scripts/test_loop.sh` can drive them; it runs in
  `make check` as `make scripts-test` (ADR-0013).
- [x] **M0.R2 Fake client handshake failure.** When the first reply is not a
  `HelloAck`, `Replay` awaits the close reason and hangs if the peer keeps the
  socket open; the `IllegalStateException` escapes `Main` as a stack trace.
  Accept: `./gradlew :tools:fake-client:test` — a test server that answers
  with a non-`HelloAck` frame and stays open makes `replay` fail within a
  bounded time with a clear error.
  Deviation: also bounds a peer that never replies (10 s handshake timeout).
- [x] **M0.R3 mDNS partial failure.** `MdnsAdvertiser` leaks the JmDNS
  instances already created when a later interface throws, and
  `AppMonitorServer.start` leaves the HTTP server bound with no advertiser.
  Accept: `./gradlew :desktop:server:test` — a test that makes registration
  fail on one address shows every created instance closed and the port free.
  Deviation: `MdnsAdvertiser.start` and `AppMonitorServer` take an internal
  registration/advertiser factory so the failure is injected without a LAN.
- [x] **M0.R4 Body rendering cost and strictness.** `indentJson` is O(n²)
  (`nextSignificant` copies the rest of the body per bracket); `render` is not
  memoized in `App.kt`; bare words nested in objects/arrays (`{"a": foo}`)
  are accepted as JSON.
  Accept: `./gradlew :desktop:app:test` — tests for a nested bare word
  (not JSON) and for a multi-MB JSON body formatted in linear time.
  Deviation: numbers with leading zeros are rejected too. Rendering stays on
  the UI thread with no size cap (DEBT-0006).
- [x] **M0.R5 Comment length.** KDoc blocks over 4 lines in `desktop/server`
  (`DeviceSession.kt`, `AppMonitorServer.kt`, `ServerConfig.kt`) and any other
  source block breaking `docs/guidelines/02_comments.md`.
  Accept: no comment block over 4 lines outside public SDK API (grep in the PR).
  Deviation: one more block found, in `tools/fake-client` `Replay.kt`. The
  server's single-use rule moved to the SDD.

### Second milestone review

Findings of the `code-review` of `d8fbae9..a3046dd` (after M0.R1–M0.R5).
One became a debt: DEBT-0007 (mDNS failure on one interface stops the whole
server).

- [x] **M0.R6 Server lifecycle.** `AppMonitorServer.stop` closes the advertiser
  outside a `try`/`finally`, and `MdnsAdvertiser.close` stops at the first
  failing registration, leaving the port bound and `sessions` open; `start`
  after `stop` passes its check although `newSessions` is closed; the
  handshake waits for the first frame with no timeout.
  Accept: `./gradlew :desktop:server:test` — a test where one registration
  close throws shows every registration closed, the port free and `sessions`
  completed; `start` after `stop` fails; a client that connects and never
  sends `Hello` is closed within a bounded time.
  Deviation: `stop` rethrows the first close failure after cleaning up; the
  10 s handshake timeout is an internal constructor parameter, like the
  advertiser factory, so the test bounds it at 200 ms.
- [x] **M0.R7 Fake client errors and discovery leak.** `discover` leaks the
  JmDNS browsers already created when a later `JmDNS.create` throws; `Main`
  only catches `IOException`/`HandshakeException`, so an unresolvable host or
  a non-WebSocket endpoint crashes with a stack trace, and a malformed
  `HelloAck` payload is reported as an unreachable desktop.
  Accept: `./gradlew :tools:fake-client:test` — tests for an unresolvable
  host and an HTTP 404 endpoint exit with a one-line error (no stack trace),
  a malformed `HelloAck` raises `HandshakeException`, and a failing browser
  creation closes the ones already created.
  Deviation: the CLI body moved from `main` to an internal `run` returning the
  exit status, so tests drive it; `discover` takes internal address and
  browser factories, like `MdnsAdvertiser`. mDNS failures exit 1 too.
- [x] **M0.R8 Desktop app startup errors and body decoding cost.** The app's
  `LaunchedEffect` only catches `IOException`, so other startup failures skip
  the "Server failed" status; `BodyFormat` decodes and parses a JSON body
  twice per render, and `hexDump` boxes every byte and calls `String.format`
  per byte.
  Accept: `./gradlew :desktop:app:test` — a test where the server start
  throws a non-IO exception shows the failed status; a multi-MB hex dump
  formats in linear time within the existing bound.
  Deviation: the startup body moved from `main` to an internal `runServer`,
  tested with a stopped (single-use) server; a test also pins that the
  default format and rendering share one decode.
- [x] **M0.R9 Loop log and CI cancellation.** `run_session` writes stderr into
  the `.jsonl` that `jq` parses, so one non-JSON line hides the result and the
  rate-limit events; the CI concurrency group cancels in-progress `dev` push
  runs, which ADR-0012 relies on to seed the Gradle cache.
  Accept: `make scripts-test` — a session that prints a non-JSON stderr line
  still yields its verdict; `ci.yml` cancels in progress only for
  `pull_request` events.
  Deviation: stderr goes to `<log>.err` beside the `.jsonl`, which also keeps
  `make loop-watch` (jq on the same file) alive.

### Third milestone review

Findings of the `code-review` of `d8fbae9..ec3c5e6` (after M0.R6–M0.R9).
Two became debts: DEBT-0008 (accepted channels not enforced) and DEBT-0009
(discovery keeps the first resolve, before the TXT record). The zero-address
advertising case joined DEBT-0007.

- [x] **M0.R10 Server lifecycle under cancellation.** `start` assigns `server`
  only after `startSuspend` returns, and drops the advertiser built by
  `startAdvertiser` when cancelled on resume, so `stop` frees neither;
  `newSessions.send` after `stop` throws outside the session `try`/`finally`;
  `runServer` lets a `stop` failure escape the `LaunchedEffect` and mask the
  original failure.
  Accept: `./gradlew :desktop:server:test :desktop:app:test` — a test that
  cancels `start` during advertising shows every registration closed and the
  port free; a test where `stop` throws shows `runServer` reporting it through
  `onStatus` without throwing.
  Deviation: `runServer` takes an internal `stop` parameter, since the
  server's failing-advertiser constructor is not visible from `desktop/app`.
  The handshake-after-`stop` path (close 1001) has no test: it needs a race.
- [x] **M0.R11 CLI arguments and close reason.** The fake client accepts
  `--timeout` of 0 or less and reports it as "no desktop found"; the server's
  `--port` parse failure is a stack trace; `Replay` truncates the close reason
  by UTF-16 chars, not the 123 UTF-8 bytes RFC 6455 allows.
  Accept: `./gradlew :tools:fake-client:test :desktop:server:test` — tests
  for `--timeout 0` (usage, exit 2), a bad `--port` (one-line error, no stack
  trace) and a multibyte close reason that fits in 123 bytes.
  Deviation: `--port` out of range is rejected too (server 0–65535, fake
  client 1–65535), and the server rejects unknown arguments; its parsing
  moved to an internal `parsePort`, tested directly. A bind failure on a
  valid port still ends in a stack trace (DEBT-0010).

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`).
