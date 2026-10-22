---
id: PLAN-0001
title: Foundation
status: Done # Draft | Active | Done | Abandoned
milestone: M0
started: 2026-10-21
finished: 2026-10-22
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

### Fourth milestone review

Findings of the `code-review` of `d8fbae9..c8b2074` (after M0.R10–M0.R11).
One joined a debt: the body copied by `toByteArray` per decode and hex dump
(DEBT-0006).

- [x] **M0.R12 Server start/stop races.** A failure in `release()` inside
  `start`'s catch replaces the original bind failure or cancellation; `stop`
  during an in-flight `start` lets `start` assign the advertiser after
  `release()` ran, so the mDNS registration leaks and `start` returns a port
  for a stopped server.
  Accept: `./gradlew :desktop:server:test` — a test where `start` fails and
  the advertiser close also throws sees the original failure (the close one
  suppressed); a test that calls `stop` while the advertiser is being created
  shows that advertiser closed and `start` failing.
  Deviation: `start` releases its own server and advertiser (locals), not
  the fields `stop` may have already taken; a lock orders `stopped` and the
  advertiser hand-off. The first test fails `start` by cancellation.
- [x] **M0.R13 Loop verdict, watchdog and worktree.** `loop.sh` compares the
  verdict exactly, so `LOOP: CONTINUE` with a trailing space or CR stops the
  loop; a TERM that reaches the watchdog before `sleeper=$!` orphans the
  `sleep`; `make loop-worktree` re-implements `ensure_worktree` and ignores
  `LOOP_WORKTREE`.
  Accept: `make scripts-test` — a verdict line with trailing whitespace or CR
  continues; an immediate TERM leaves no `sleep` behind; `make loop-worktree`
  goes through `loop.sh`'s `ensure_worktree`.
  Deviation: no `TERM` cancel could be made race-free in bash 3.2, so the
  watchdog polls a `.done` marker (ADR-0014); the test runs instant sessions
  instead of sending `TERM`. Verdicts go through a new `verdict_of`;
  `make loop-worktree` calls `loop.sh --worktree`.
- [x] **M0.R14 Client and app edge cases.** `Replay` awaits `closeReason`
  with no bound after the channel closes; `runServer` misses an `Error` at
  startup, leaving "Starting server…"; the body format state is keyed on the
  body bytes only, so an exchange with the same bytes keeps the previous
  exchange's format; `--help` is matched as the value of another option.
  Accept: `./gradlew :tools:fake-client:test :desktop:app:test` — tests for a
  dropped connection failing within the handshake bound, an `Error` from
  `start` reported through `onStatus`, two exchanges with the same body and
  different `Content-Type` opening in their own default format, and
  `--name --help` not printing usage.
  Deviation: a real drop completes Ktor's close reason, so the unbounded
  await is pinned by a unit test of `awaitWithin` with a never-completing
  deferred. A drop while sending leaked a `CancellationException`; `replay`
  now raises an `IOException`. `runServer` takes an internal `start`.

### Fifth milestone review

Findings of the `code-review` of `d8fbae9..248bcb1` (after M0.R12–M0.R14).
Three became debts: DEBT-0011 (fake client discovery blocks the JmDNS
listener and matches names case-sensitively), DEBT-0012 (per-address JmDNS
instances share a host and service name) and DEBT-0013 (loop timeout leaves
the session's children running). The O(n) rescan per recomposition is already
DEBT-0004; the missing WebSocket ping and `maxFrameSize` are already in the
M1 roadmap hardening.

- [x] **M0.R15 Stop during start.** `start` publishes `server` before
  `startSuspend`, so a `stop` that runs while the engine is starting calls
  `stopSuspend` on it and returns while `startSuspend` may still bind; the
  port is freed only when `start` reaches its `stopped` check.
  Accept: `./gradlew :desktop:server:test` — a test that calls `stop` while
  `start` is inside `startSuspend` sees the port free when `stop` returns and
  `start` failing.
  Deviation: `stop` awaits the in-flight `start`, which releases its own
  engine; the engine start is an internal constructor parameter so the test
  holds `start` inside it. The advertiser-creation test now stops concurrently.
- [x] **M0.R16 Shutdown on exit.** The headless server adds its shutdown hook
  only after `start` returns, so a SIGINT during startup skips `stop`; the
  desktop app relies on cancelling the `LaunchedEffect`, and nothing waits for
  the `NonCancellable` `stop` before `application {}` returns, so mDNS goodbye
  and port release can be cut off.
  Accept: `./gradlew :desktop:server:test :desktop:app:test` — tests show the
  headless server stops a server whose `start` is still running when the hook
  fires, and the app's exit path awaits `stop` before returning.
  Deviation: `application {}` calls `System.exit` once its composition ends
  (Compose 1.12.2 bytecode), so the app now uses `awaitApplication` with the
  server outside the composition and calls `exitProcess` itself.

### Sixth milestone review

Findings of the `code-review` of `d8fbae9..6fbbaed` (after M0.R15–M0.R16).
One joined a debt: rows compare the selected exchange by data-class equality,
deep-comparing bodies (DEBT-0004). Two were rejected: the body format keyed
on the body alone cannot go stale, since a body and its headers arrive in the
same event; the two port ranges differ on purpose (bind accepts 0, connect
does not).

- [x] **M0.R17 Desktop app shutdown.** The app registers no shutdown hook, so
  SIGINT or SIGTERM skips `stop`; `runServer` catches only `Exception` around
  `stop`, so an `Error` escapes and masks the first failure; `stop` awaits an
  in-flight `start` with no bound, so a `start` stuck in mDNS registration
  hangs the headless hook and the app's exit.
  Accept: `./gradlew :desktop:server:test :desktop:app:test` — a test shows the
  app's shutdown hook stops the server; an `Error` from `stop` is reported
  through `onStatus`; a hook whose `stop` never returns gives up within a
  bounded time.
  Deviation: the bounded hook is `shutdownHook` in `desktop/server`, shared
  by the headless server and the app; `runServer` also bounds its `stop`.
  CI exposed a race in two `ServerLifecycleTest` stop-during-start tests
  (`stop` dispatched after `start` finished); `stop` now starts undispatched.
  `MainTest`'s hook test no longer asserts `start` fails: the hook's worker
  thread may stop after `start` returns; it still asserts the port is freed.
- [x] **M0.R18 Fake client ignores a close after HelloAck.** `replay` sends
  every envelope and reports success even when the desktop closed the session
  with `PROTOCOL_ERROR`, since buffered sends do not throw.
  Accept: `./gradlew :tools:fake-client:test` — a test server that acks, then
  closes with `PROTOCOL_ERROR` makes `replay` fail with the close reason, and
  the CLI exits 1.
  Deviation: `replay` now uses Ktor's raw CIO session (`webSocketRawSession`),
  since the default session completes `closeReason` with its own close; it
  sends a normal close after the traffic and fails on any non-`NORMAL` reply.
- [x] **M0.R19 Loop timeout marker.** `run_session` removes a stale `.done`
  but not a stale `.timeout`, so a reused log path always returns 124; the
  watchdog's `kill` succeeds on an exited, unreaped session, so a session that
  ends at the deadline is reported as timed out.
  Accept: `make scripts-test` — a run after a timed-out run on the same log
  path returns the command's code; a session that exits by itself returns its
  code even when the timeout marker appears.
  Deviation: the marker cannot tell an exit at the deadline from a kill, so
  `run_session` also requires exit status 143 (ADR-0015); the test stands
  in for the deadline race with a session that ignores `TERM`.

### Seventh milestone review

Findings of the `code-review` of `d8fbae9..47e3919` (after M0.R17–M0.R19).
Two became debts: DEBT-0015 (the 5 s stop bound may cut a slow sequential
JmDNS close) and DEBT-0016 (a watchdog kill that does not exit 143 is not
reported as a timeout).

- [x] **M0.R20 Stop during a failed start.** `start` clears `startup` before
  `release`, so a `stop` that runs in between returns while the failed start
  still holds the port and mDNS; a second `stop` (the shutdown hook after
  `runServer` gave up) returns at once while the first release still runs;
  `start` builds the engine before its single-use checks.
  Accept: `./gradlew :desktop:server:test` — a test that calls `stop` while a
  failing `start` is releasing sees the port free when `stop` returns; a
  second `stop` during a slow first release waits for it; `start` after
  `stop` fails without building an engine.
- [x] **M0.R21 Desktop app exit paths.** `main` calls `exitProcess` only when
  `runBlocking` returns, so a UI failure leaves the JVM alive on AWT threads;
  `runServer` rethrows any `CancellationException` from `start` or the
  session collection, leaving "Starting server…" when its own coroutine is
  still active.
  Accept: `./gradlew :desktop:app:test` — a `CancellationException` thrown by
  `start` in an active coroutine is reported through `onStatus`; the exit
  path runs even when the UI throws.
  Deviation: the exit path is `exitAfter`, which exits 1 after printing a UI
  failure instead of 0.
- [x] **M0.R22 Fake client handshake diagnosis.** `fail` closes the session
  before throwing `HandshakeException`; on a dropped connection the close
  throws and `replay` reports "Connection to the desktop dropped" instead of
  the handshake error; the `replay` KDoc is 5 lines, over the comment limit.
  Accept: `./gradlew :tools:fake-client:test` — a server that answers with a
  non-`HelloAck` frame and drops the connection makes `replay` raise
  `HandshakeException`; no comment block over 4 lines (grep in the PR).
  Deviation: no code fix; Ktor 3.6.0's `WebSocketSession.close(reason)`
  swallows any send or flush failure, so `fail` already throws
  `HandshakeException`; the test pins it, and the KDoc is shortened.
- [x] **M0.R23 Loop watch follows the next session.** `loop_watch.sh` picks
  the newest `.jsonl` once, so it keeps following a finished session when the
  loop starts the next one.
  Accept: `make scripts-test` — with a newer `.jsonl` created while watching,
  the watcher prints that session's events.

### Eighth milestone review

Findings of the `code-review` of `d8fbae9..2e3d390` (after M0.R20–M0.R23).
One joined a debt: `TrafficState.received` decodes the payload inside the
`MutableStateFlow.update` lambda, so a lost compare-and-set decodes again
(DEBT-0004). One was already tracked: `toByteArray` copies in `BodyFormat`
(DEBT-0006). One was rejected: the fake client's two identical catch branches
for `HandshakeException` and `SessionClosedException` are a style choice.

- [x] **M0.R24 Server stop under cancellation.** `stop` runs `release` in the
  caller's context, so when `runServer`'s `withTimeout` cancels it mid-close,
  `stopSuspend` throws at its first suspension, the engine keeps the port, and
  a later `stop` (the shutdown hook) returns at once; `MdnsAdvertiser.start`
  rolls back only on `Exception`, so an `Error` from a later address leaks the
  registrations already made.
  Accept: `./gradlew :desktop:server:test` — a test that cancels `stop`
  while the advertiser close is in progress sees the port free once that
  close returns; a registration throwing an `Error` on the second address
  closes the first.
- [x] **M0.R25 Desktop app server-stopped status.** When `sessions` completes
  without a failure (the server stopped by someone else), `runServer` leaves
  "Listening on port N"; the `runServer` KDoc is 5 lines, over the comment
  limit.
  Accept: `./gradlew :desktop:app:test` — a test where `sessions` completes
  reports a stopped status through `onStatus`; no comment block over 4 lines
  (grep in the PR).
- [x] **M0.R26 Loop usage-limit detection.** With no verdict line, `loop.sh`
  also greps the free-text report for `usage limit|rate limit`, so a crashed
  session whose report only mentions rate limiting waits and retries up to
  `LIMIT_RETRIES` times instead of stopping.
  Accept: `make scripts-test` — a session with no verdict whose report
  mentions "rate limit" and whose `rate_limit_event` is `allowed` stops with
  "no verdict line"; a session with a non-`allowed` limit event still waits.
  Deviation: the tests run `loop.sh` end to end with a stub `claude` on
  `PATH` and `LIMIT_RETRIES=0`, so the wait branch exits without sleeping.

### Ninth milestone review

Findings of the `code-review` of `d8fbae9..f667405` (after M0.R24–M0.R26).
One became a debt: DEBT-0018 (network events without a request id merge into
one exchange). One was already tracked: `exchangesOf` re-filters every
exchange on each recomposition (DEBT-0004). One was rejected: M0.R26's text
describes the defect it fixed, as every review task does; its Accept line
states the shipped behaviour.

- [x] **M0.R27 Server stop failures and late handshakes.** (#48) Only the
  first `stop` caller awaits the release; a later one only joins, so when
  the first is cancelled (`runServer`'s timeout) a failing mDNS close is never
  reported; `MdnsAdvertiser.close` catches only `Exception`, so an `Error`
  from one registration leaves the others open; the server sends `HelloAck`
  before checking that the session can be published, so a device connecting
  during `stop` gets a successful handshake and then a `GOING_AWAY` close.
  Accept: `./gradlew :desktop:server:test` — a second `stop` after a cancelled
  first one rethrows the advertiser close failure; a registration whose close
  throws an `Error` still lets the others close; a client handshaking after
  `stop` began gets no `HelloAck`.
  Deviation: every `stop` now awaits and rethrows the release failure; the
  session is published under the lock `stop` takes, before `HelloAck`.
- [x] **M0.R28 Loop verdict with trailing text.** `verdict_of` matches any
  line starting with `LOOP: CONTINUE`, but the loop compares it to the exact
  string, so `LOOP: CONTINUE — next task` stops the loop with exit 0 as if
  it were a deliberate stop.
  Accept: `make scripts-test` — a report ending in `LOOP: CONTINUE` followed
  by more text either continues or stops with "no verdict line"; it never
  exits 0 reporting a CONTINUE as a stop.
  Deviation: such a line is no verdict (stops with "no verdict line"), and
  so is `LOOP: STOP` glued to a word (`LOOP: STOPPED`).
- [x] **M0.R29 Fake client discovery timeout.** The `--timeout` budget starts
  only after every JmDNS instance is created, so `JmDNS.create` on each
  interface is outside the bound the CLI documents.
  Accept: `./gradlew :tools:fake-client:test` — with a browser factory that
  blocks longer than `--timeout`, `discover` returns within the timeout plus
  a small margin, and the browsers it created are closed.
  Deviation: browsers open in a detached IO coroutine; one that finishes
  opening after `discover` returned is closed on arrival.

### Tenth milestone review

Findings of the `code-review` of `d8fbae9..45a7700` (after M0.R27–M0.R29).
One was already tracked: `utf8OrNull` and `hexDump` copy the whole body
(DEBT-0006). One was rejected: `scripts/test_loop.sh` does remove its temp
directory, with an explicit `rm -rf` before the final exit.

- [x] **M0.R30 Fake client discovery skips a failing address.** The first
  `browse` failure fails the whole discovery, so one interface where JmDNS
  cannot open (VPN, bridge) hides a desktop advertised on another.
  Accept: `./gradlew :tools:fake-client:test` — with a browser factory that
  throws for the first address, `discover` still finds the service on the
  second; it fails only when every address fails.
- [x] **M0.R31 Desktop app single stop timeout on exit.** When `stop` hangs,
  `runServer` waits `STOP_TIMEOUT`, then `exitProcess` runs the shutdown hook,
  which waits the same release another `STOP_TIMEOUT` and reports the failure
  a second time.
  Accept: `./gradlew :desktop:app:test` — with a `stop` that never returns,
  closing the window exits after one `STOP_TIMEOUT` (plus a small margin) and
  reports the timeout once.
- [x] **M0.R32 Server stop sees a release failure in start.** When `stop`
  runs during `start`, `start` releases the advertiser itself and a failing
  mDNS close reaches only `start`'s caller; `stop` returns normally, against
  its KDoc.
  Accept: `./gradlew :desktop:server:test` — `stop` racing `start`, with an
  advertiser whose close throws, rethrows that failure from `stop`.
- [x] **M0.R33 Loop verdict on the last line only.** `verdict_of` takes the
  last matching line anywhere in the report, so a report that quotes
  `LOOP: CONTINUE` mid-text and ends without a verdict continues the loop.
  Accept: `make scripts-test` — a report with `LOOP: CONTINUE` followed by
  non-verdict lines stops with "no verdict line"; trailing blank lines after
  the verdict are still accepted.
- [x] **M0.R34 Fixture regeneration only when asked.** Any value of the
  `updateFixtures` Gradle property, `false` included, regenerates the golden
  fixtures.
  Accept: with one fixture edited by hand,
  `./gradlew :protocol:jvmTest -PupdateFixtures=false` fails the contract
  test and leaves the edit in place; `-PupdateFixtures` alone still
  regenerates it.

### Eleventh milestone review

Findings of the `code-review` of `d8fbae9..b3ae9bd` (after M0.R30–M0.R34).
One was already tracked: an advertiser with zero LAN addresses starts
silently (DEBT-0007). Three were rejected: the body format keyed on the body
alone (rejected in the sixth review, same reason); publishing the session
before `HelloAck` is M0.R27's fix, and a client that drops after `Hello`
shows as connected, then disconnected, which is what happened; the missing
EXIT trap in `scripts/test_loop.sh` is deliberate (bash 5 runs it in the
watchdog subshells).

- [x] **M0.R35 Loop worktree failure and notification quoting.**
  `ensure_worktree` ends with an `echo`, so a failed `git fetch` or
  `git worktree add` returns 0 and `make loop-worktree` succeeds; `make loop`
  ignores its status. `notify` puts the message in an AppleScript literal
  unescaped, so a `"` drops the notification. The test's `pgrep -f "sleep
  0.11"` matches any process on the machine.
  Accept: `make scripts-test` — `loop.sh --worktree` with an unusable
  `LOOP_WORKTREE` (an existing file) exits non-zero and does not print
  "created worktree"; the leaked-sleep check only sees the test's own
  processes.
- [x] **M0.R36 Fake client help and comment length.** `--help` prints the
  usage to stderr; the `discover` KDoc is 5 lines, over the comment limit.
  Accept: `./gradlew :tools:fake-client:test` — `--help` writes the usage to
  `out`, exits 0 and leaves `err` empty; no comment block over 4 lines (grep
  in the PR).
- [x] **M0.R37 Fixture regeneration once, stale files removed.** The
  regeneration runs in `FixtureTest`'s `init`, once per test method, and
  never deletes a `.bin` whose sample was removed, so
  `everyFixtureHasASample` fails right after `-PupdateFixtures`.
  Accept: with a stray `protocol/fixtures/stale.bin`,
  `./gradlew :protocol:jvmTest -PupdateFixtures` passes and removes it; the
  fixtures are written once per run.
- [x] **M0.R38 Desktop app stop hook default.** `addStopHook` repeats
  `shutdownHook`'s default `onFailure`, so the two can drift.
  Accept: `./gradlew :desktop:app:test` — `addStopHook` has no copy of the
  default message; its tests still pass.
- [x] **M0.R39 Flaky discovery skip test.** The `dev` push CI run for #56
  failed `DiscoveryTest` "an address whose browser fails is skipped…" (run
  39834502803), and the same code passed on #57: the browser for the
  resolved address is closed by the detached IO coroutine, which may not have
  run by the time the test asserts `closed`.
  Accept: `./gradlew :tools:fake-client:test --rerun` 20 times in a row
  without a failure; either `discover` closes every browser it opened before
  returning, or the test waits for the close within a bound.

## Outcome

- **Shipped**: M0.1 Gradle skeleton (#4); M0.2 protocol schema, Wire codegen
  and golden fixtures (#5); M0.3 desktop WebSocket server with handshake,
  sessions and mDNS (#6); loop hardening with worktree, timeout and
  checkpoint review (#7); M0.4 fake client and headless server (#8); M0.5
  Compose desktop app skeleton (#9); M0.6 PR CI (#10); M0.7 docs sync (#11).
  Eleven milestone review rounds then shipped M0.R1–M0.R39 in #12–#63: plan
  updates in #12, #19, #24, #27, #31, #34, #38, #43, #47, #51, #57, fixes in
  the rest, and the review bound in #62.
- **Decisions**: ADR-0006 to ADR-0016. ADR-0016 caps the milestone review at
  three incremental, correctness-only rounds; this plan closed under it.
- **Debt**: DEBT-0001 to DEBT-0018 created, all open; none paid. DEBT-0002's
  trigger fired at M0.5 and was re-armed after measuring. No review finding
  is left open outside these debts: every task of the eleventh round is done.
- **Deviations**: per task above. The milestone review added 39 tasks to the
  7 planned (M0.R1–M0.R39) and 52 of the plan's 60 PRs (#4–#63).
  `AppMonitorServer` lifecycle alone took eight tasks (R6, R10, R12, R15, R20, R24, R27, R32).
  The owner checkpoint of M0.7 moved to this close-plan PR.
- **Lessons**: an unbounded "no findings" exit never converges, since each
  fix is reviewed again and the diff only grows (ADR-0016). Concurrent
  start/stop lifecycles need their states and transitions written down
  before the code, not discovered race by race in review. Tests that depend
  on a coroutine finishing (R39) or on a LAN interface flake in CI; wait on
  the observable effect with a bound. Hardening found here that real devices
  need (body size limit, `maxFrameSize`, keep-alive, `Hello` timeout) is in
  the M1 roadmap.
