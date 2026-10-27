---
id: PLAN-0009
title: iOS deep capture
status: Done # Draft | Active | Done | Abandoned
milestone: M8
started: 2026-10-25
finished: 2026-10-25
---

# PLAN-0009 — iOS deep capture (M8)

## Goal

An iOS app's traffic that `URLSession` capture misses reaches the desktop
through ADR-0004's layers 2 to 4: grpc-swift calls in full through an
adapter (`ADAPTER`), every other socket the app opens as connection
metadata, host, port, bytes, timing and error (`CONNECTION`), and, with the
desktop's proxy mode switched on, HTTP/1.1, HTTP/2 and gRPC in full from a
simulator whose root store trusts the desktop's CA (`PROXY`).

## Tasks

M7's lesson applied: a value one writer produces and another reads back
is tested with what the other writer really sends. Here that means a
`CONNECTION` or `PROXY` exchange, which has no status or no body the way
an SDK one does, goes through every reader the window and MCP have
(list, detail, benchmarks, run diff, HAR, cURL, search). M6's kept: the
lifecycle of each new server (the proxy) is a table of call class
against state, with the test that pins each cell. M4's kept: decisions in
the "stop and ask" areas come first, in one stop, and the platform
primitives (library versions and licences) are written down with their
evidence before the code.

- [x] **M8.1 Deep capture decisions.** ADR-0099, ADR-0100 and ADR-0101
  `Accepted` (ADR-0100 D still open), one stop: (a) the grpc-swift adapter's major version, its
  product and the setup step it adds; (b) what the connection layer
  captures, whether it is on by default, and the owner's Instruments
  finding on which SDK the old tool missed (ADR-0004); (c) the proxy's
  binding, how simulator traffic reaches it, the CA's lifetime and
  storage, its install on the simulator, and duplicates with the SDK's
  own capture.
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [x] **M8.2 grpc-swift adapter.** The interceptor per ADR-0099 in
  `sdk/ios`, unary and streaming calls as `ADAPTER` exchanges (method
  path, metadata, messages, `grpc-status`), its product in both
  manifests, its public API dumped and checked in debug and release
  (ADR-0048); SDD 07 and the README's setup.
  Accept: `make swift` and `make publish-dry-run` — a unary and a
  server-streaming call against an in-process grpc-swift server show up
  with their status and messages; the SwiftPM consumer builds with and
  without the adapter.
- [x] **M8.3 Connection metadata.** (`feat/ios-connection-metadata`; BSD sockets only, `nw_connection_*` left to DEBT-0076; `sent_size` added to `ResponseReceived`, ADR-0103) Symbol rebinding per ADR-0100 in
  `sdk/ios`, `CONNECTION` exchanges sent through the existing transport,
  the SDK's own socket to the desktop and `URLSession`'s already captured
  traffic not reported twice; SDD 06 or 07 names the rebound symbols and
  what each one reports.
  Accept: `make swift` and `make swift-sim` — a raw BSD socket client in
  the test target shows up as one connection with host, port, bytes and
  duration, a refused port with its error, nothing for the SDK's own
  connection.
- [x] **M8.4 Connection exchanges in the desktop.** (`feat/desktop-connection-exchanges`; ADR-0104; HAR leaves connections out, two `connection_*.bin` fixtures added, run diff checked in storage and MCP, the window reusing `diffEndpoints`) `desktop/storage`,
  `desktop/app` and `desktop/mcp` show a `CONNECTION` exchange as an
  opaque connection (no status, no body): list row, detail, the layer
  caption, benchmarks, run diff, HAR and cURL (refused or marked), search.
  Accept: `./gradlew :desktop:storage:test :desktop:mcp:test :desktop:app:test`
  — a `CONNECTION` envelope from the fixtures through every reader above,
  an offscreen screenshot `connections.png`.
- [x] **M8.5 Proxy mode, HTTP/1.1.** (`feat/desktop-proxy-http1`; ADR-0105; screenshot `proxy.png`; the pass-through list of ADR-0101 E.1 and frames after a `101` left to DEBT-0078 and DEBT-0077; `jdk.crypto.ec` added to the packaged runtime) A `desktop/proxy` module per
  ADR-0101, off by default and switched on in the settings: CA generated
  and kept per ADR-0101, `CONNECT` with TLS interception, plain HTTP,
  exchanges stored as `PROXY` with their device unknown; ADR `Accepted`
  for the proxy and certificate libraries (versions and licences from
  their official sources); SDD chapter with the lifecycle table.
  Accept: `./gradlew :desktop:proxy:test :desktop:app:test` — curl
  through the proxy with `--cacert` gets its response and the exchange is
  in the store; a host with a pinned or refused certificate shows as a
  failed exchange, not a hang; port taken; an offscreen screenshot of the
  settings.
- [x] **M8.6 Proxy HTTP/2 and gRPC.** (`feat/desktop-proxy-http2`; split from the plan's M8.6, whose simulator half is M8.8 and whose DEBT-0075 is M8.7; ADR-0106; grpc-java plaintext through a `CONNECT` tunnel, TLS HTTP/2 by curl; DEBT-0079) HTTP/2 through the
  proxy with gRPC messages decoded as frames.
  Accept: `./gradlew :desktop:proxy:test` — curl `--http2` and a grpc-java
  client through the proxy, a unary and a streaming call stored with
  their messages and `grpc-status`.
- [x] **M8.7 The desktop reads grpc-status.** (`feat/desktop-grpc-status`; ADR-0107; a `grpc_status` column, migration `2.sqm`; the list shows `grpc N`; `failed_only` unchanged) DEBT-0075: a call with
  `grpc-status` other than 0 in its response headers counts as an error
  wherever `status_code` decides one (list, issues, benchmarks, run diff,
  MCP), from both writers: the `AppMonitorGRPC` adapter's fixture and
  the proxy's.
  Accept: `./gradlew :desktop:storage:test :desktop:mcp:test :desktop:app:test`
  — a failed gRPC call from each writer shows as an error in every
  reader above.
- [x] **M8.8 The proxy and the simulator.** (`feat/ios-sample-grpc-proxy`; ADR-0108 option 1, replacing ADR-0101 D.1; ADR-0109: gRPC-Core's C API through a bridging header, `make server --proxy`, a TLS gRPC origin in `tools/e2e-target`; the Release app links part of gRPC-Core; DEBT-0080) First check that a
  simulator app reads a file under the Mac's data directory. Then the
  settings' proxy command sets `grpc_proxy` and
  `GRPC_DEFAULT_SSL_ROOTS_FILE_PATH` through `SIMCTL_CHILD_`, and an iOS
  sample button makes a TLS gRPC-Core call (`grpc/grpc-ios`, Debug only).
  Accept: `./gradlew :desktop:app:test` (the command's text) and
  `make e2e-ios` with the proxy on — the sample's gRPC call stored as a
  `PROXY` exchange with its messages.

### Milestone review

Findings of the `code-review` of `d5f2e82..5115fdb`. None is wrong data,
a crash, a hang or a leak in normal use, so no task was added. Eight went
to DEBT-0081: a request body dropped after the origin answers early, no
backpressure in the HTTP/1.1 and HTTP/2 relays, an fd slot kept when a
socket is closed outside the rebound `close`, an empty "Proxy" session
after a failed start, connections counted against the HAR cap, connections
marked truncated, the iOS end to end reading the gRPC port too early, and
the two `statusOf` functions. None was rejected.

## Outcome

- **Shipped**: plan and ADR-0099 to ADR-0101 (#225); M8.2 the
  `AppMonitorGRPC` adapter for grpc-swift (#226); M8.3 BSD socket
  connections through symbol rebinding, with `sent_size` (#227); M8.4
  connection exchanges in every desktop reader (#228); M8.5 the proxy
  mode with HTTP/1.1 and TLS interception (#229); M8.6 HTTP/2 and gRPC
  through the proxy (#230); M8.7 `grpc-status` read as an error (#231);
  ADR-0108 (#232); M8.8 a gRPC-Core call from the iOS sample through the
  proxy, checked by `make e2e-ios` (#233). One review round, no task; the
  close is in this PR.
- **Decisions**: ADR-0099 (grpc-swift 2 adapter product), ADR-0100
  (connection layer; D, the owner's Instruments finding, is still open),
  ADR-0101 (proxy mode), ADR-0102 (gRPC call events), ADR-0103
  (connection events), ADR-0104 (connections in the desktop), ADR-0105
  (Netty and Bouncy Castle), ADR-0106 (proxy HTTP/2 and gRPC), ADR-0107
  (`grpc_status` column), ADR-0108 (simulator trust through
  `GRPC_DEFAULT_SSL_ROOTS_FILE_PATH`, replacing ADR-0101 D.1), ADR-0109
  (gRPC-Core in the iOS sample).
- **Debt**: DEBT-0075 created and paid by M8.7. DEBT-0076 to DEBT-0080
  created; DEBT-0076 to DEBT-0079 fired on this review and were
  re-triggered on their developer events or the next plan that changes
  the code they name. DEBT-0081 created with the review's eight findings.
  DEBT-0060, DEBT-0064, DEBT-0067, DEBT-0068, DEBT-0071, DEBT-0072 and
  DEBT-0074 fired as their files were touched and were partly paid or
  re-triggered. No correctness finding that normal use hits is left open.
- **Deviations**: M8.6 was split three ways. The proxy's HTTP/2 stayed
  M8.6, DEBT-0075 became M8.7, and the simulator half became M8.8, whose
  approach ADR-0108 changed from the keychain to a roots file. M8.3
  rebinds BSD sockets only (`nw_connection_*` is DEBT-0076). The plan
  ended with eight tasks instead of six.
- **Lessons**: M7's lesson held. Every reader was tested with what the
  other writer really sends: the `CONNECTION` and `PROXY` fixtures, and
  the adapter's and the proxy's failed gRPC calls. The review found no
  wrong reading in the list, detail, MCP, HAR or run diff. What slipped
  was the relays' streaming behaviour: an origin that answers before the
  body ends, and a slow peer under a large stream. The next plan that
  changes a relay should test both against a real origin. Splitting a
  task as soon as its second half needed its own decision (M8.6 into
  M8.6 to M8.8) kept each PR to one Accept line.
