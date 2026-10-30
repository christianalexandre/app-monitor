---
id: PLAN-0016
title: Debt sweep before distribution
status: Draft # Draft | Active | Done | Abandoned
milestone: H6
started:
finished:
---

# PLAN-0016 — Debt sweep before distribution (H6)

## Goal

M10 starts with a backlog where every open DEBT waits on something
outside this repository or on the owner. Each DEBT below is paid, or
`Dropped` with its reason, in its task's PR. A trigger that has fired
before and was put off ("the next PR that touches …") is not put off
again: the item is paid, dropped, or its trigger becomes an event that
has not happened yet.

Follows PLAN-0015 (H5). A task may split into several PRs, one per
module or per DEBT, while the plan is `Active`.

Left open, each waiting on an event outside the repository or on the
owner: DEBT-0001 (a JmDNS release), DEBT-0019 (a concrete case for body
masking), DEBT-0029 (OkHttp exposing pings), DEBT-0046 (the hosted
runner's local network), DEBT-0069 (the owner's MCP client), DEBT-0076
(the owner's Instruments finding).

## Tasks

- [ ] **H6.1 Desktop server and discovery.** One LAN interface filter
  for the server and the fake client (DEBT-0005); one interface that
  fails JmDNS no longer stops the server, and zero advertised addresses
  is reported (DEBT-0007); a taken port is one line, not a stack trace
  (DEBT-0010); one host name per address (DEBT-0012); the stop bound
  covers a slow JmDNS close (DEBT-0015); a second desktop on the same data
  directory is refused (DEBT-0062); the headless server in its own
  module, so `desktop/server` no longer depends on the proxy (DEBT-0105);
  the fake client waits for an approval (DEBT-0028).
  Accept: `./gradlew :desktop:server:test :desktop:storage:test
  :tools:fake-client:test`; a second `make desktop` on the same
  `APPMONITOR_DATA_DIR` shows the refusal.
- [ ] **H6.2 Desktop store, proxy and controllers.** Text search and
  "failed only" on an index (DEBT-0063); the title bar follows a theme at
  once (DEBT-0095); one switch and lifecycle for the MCP and proxy
  controllers (DEBT-0102); the write queue's edges (DEBT-0106) and the
  review findings on `Ingest`, `WriteQueue` and `Storage` (DEBT-0064,
  DEBT-0067, DEBT-0068); the MCP tools' closing check in one place and
  the packaged server's version (DEBT-0071, DEBT-0072, DEBT-0073,
  DEBT-0074); the live list and the store share one decoding and one
  filter match (DEBT-0104), and the H2 and H3 review findings
  (DEBT-0092, DEBT-0093); a mock rule moves in its app's order
  (DEBT-0082).
  Accept: `./gradlew :desktop:storage:test :desktop:mcp:test
  :desktop:data:test :desktop:app:test`; `SearchTest`'s no-match search
  over 100 000 exchanges under 100 ms, its time in the PR; a theme picked
  in Settings repaints the title bar (screenshot).
- [ ] **H6.3 Kotlin SDKs.** One capture flow for `sdk/android-okhttp` and
  `sdk/ktor` (sink, mocks, breakpoints, replay) and one masking
  (DEBT-0101, DEBT-0055, DEBT-0044); Ktor's internal API kept behind one
  seam, or replaced (DEBT-0083); a Ktor engine that wraps OkHttp's is
  captured once (DEBT-0100); a cancelled iOS connect closes its socket
  (DEBT-0053); path operations safe on Apple (DEBT-0059); the M4 and M9
  review findings in `sdk/core` and `sdk/ktor` (DEBT-0058, DEBT-0061,
  DEBT-0088).
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android`
  and `make e2e-kmp`.
- [ ] **H6.4 iOS SDK.** One generic method hook instead of one per arity
  (DEBT-0103); headers CFNetwork adds at send time (DEBT-0037) and every
  end of a WebSocket (DEBT-0038) captured; a replay safe against a
  session being invalidated (DEBT-0084); breakpoints and the Bonjour
  probe run on the simulator (DEBT-0086, DEBT-0054); a desktop socket
  opened before discovery not reported (DEBT-0090); the M2 review
  findings in `Masking.swift` (DEBT-0043).
  Accept: `make swift`, `make swift-sim`, `make e2e-ios` and
  `make e2e-kmp-ios`, a held call resumed in the end to end.
- [ ] **H6.5 Proxy.** A WebSocket through the proxy shows its frames
  (DEBT-0077); the pass-through list of ADR-0101 E.1 in Settings
  (DEBT-0078); an HTTP/2 client reaches an HTTP/1.1-only origin
  (DEBT-0079); the M8 review findings in `ClientHandler.kt`,
  `Http2Relay.kt` and `connections.c` (DEBT-0081); a client refusing the
  certificate recorded on Windows (DEBT-0098); a dropped proxy event marks
  its exchange (DEBT-0107).
  Accept: `./gradlew :desktop:proxy:test :desktop:app:test`; the release
  gate's Windows job green on `ProxyServerTest`.
- [ ] **H6.6 Loop, scripts and build.** The loop's watchdog kills the
  session's process group and reports any exit after a kill as a timeout
  (DEBT-0013, DEBT-0016); `make check`'s time measured against
  DEBT-0002's bound, paid or dropped with the number; the M3 review
  findings in `e2e_lib.sh` and the Android sample (DEBT-0049,
  DEBT-0051).
  Accept: `make scripts-test`, with a session that traps `TERM` logged as
  a timeout and no child left; `make check` timed on a warm cache, the
  time in the PR; `make e2e-android`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
