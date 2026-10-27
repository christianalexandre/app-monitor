---
id: ADR-0110
title: Mock rules from the desktop, served by the SDKs
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0110 — Mock rules from the desktop, served by the SDKs

## Context

M9 makes the desktop change what an app's calls do (PLAN-0010). The first
change is a mock: a rule made on the desktop that answers a matching call
on the device without the network. This touches two "stop and ask" areas
(`AGENTS.md`): the public API of the SDKs, and security, because a peer on
the LAN can now change what the app receives.

Already decided, not reopened here:

- The protocol is bidirectional from the start. A device lists in
  `Hello.channels` the channels it can send or receive, and the desktop
  answers with the subset it accepts in `HelloAck.channels` (ADR-0001,
  `protocol/proto/appmonitor/v1/core.proto`).
- MCP tools that change app behaviour, such as mocks and replay, need a
  confirmation in the UI (ADR-0005).
- Only an approved device's traffic is taken (ADR-0083). MCP listens on
  loopback only, with a token (ADR-0090).
- Release builds hold no SDK code (ADR-0072, ADR-0073, ADR-0093). A mock
  can only ever reach a debug build.

Evidence, 2026-10-25:

- Today both SDKs drain and ignore every frame after `HelloAck`
  (`sdk/core/.../MonitorClient.kt`: "The desktop sends nothing after
  HelloAck"). An SDK released before M9 does not list the new channel.
  The desktop then sends it nothing, and if it did, the frame would be
  dropped.
- OkHttp: an application interceptor is "permitted to short-circuit and
  not call `Chain.proceed()`"
  ([OkHttp, Interceptors](https://github.com/square/okhttp/blob/master/docs/features/interceptors.md)).
  `AppMonitorInterceptor` is an application interceptor (SDD 05).
- Ktor 3.6.0: the `Send` hook's handler returns the `HttpClientCall`, and
  it may return one that never called `proceed`. The only public way to
  build a call from request and response data is the
  `HttpClientCall(client, requestData, responseData)` constructor. That
  constructor is marked `@InternalAPI`
  (`ktor-client-core-3.6.0-sources.jar`, `call/HttpClientCall.kt` and
  `plugins/api/CommonHooks.kt`).
- `URLSession`: a session asks the `URLProtocol` subclasses in its
  configuration's `protocolClasses`. A protocol whose `canInit(with:)`
  returns true takes the request. "You cannot use custom `URLProtocol`
  subclasses in conjunction with background sessions"
  ([Apple, `protocolClasses`](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/protocolclasses)).
  The capture hooks of ADR-0043 sit on CFNetwork's connection, below
  `URLProtocol`. They see no connection for a task that a protocol
  answers, so a mocked task's events are sent by the protocol itself.

## Options

### A. Where a rule is matched

1. **On the device.** The desktop sends the device's whole rule set, as
   one `interaction` envelope, at `HelloAck` and again on every change.
   The SDK matches each call locally, with no round trip. The rules live
   only while the session does: a closed desktop leaves no mock behind.
2. **On the desktop, per call.** The SDK asks the desktop before every
   call. This adds a LAN round trip to every call of the app, mocked or
   not, and needs a timeout policy for the case where the desktop does
   not answer.

Recommended: 1.

### B. What a rule matches

1. **Method (or any), host, and path with `*` segments**, all exact
   except `*`. The query string is ignored. The first enabled rule in list
   order wins. This is enough for "this endpoint returns 500" and can be
   read at a glance in the list.
2. **A regular expression on the full URL.** More power, but each SDK has
   its own regex dialect (Java, Kotlin/Native, `NSRegularExpression`), so
   one rule could match differently on Android and iOS.

Recommended: 1.

### C. What a mock answers

1. **A response or a failure.** A response is a status, headers and a body
   of at most `Protocol.MAX_BODY_BYTES`. A failure is a transport error,
   reported the way each client reports its own (`IOException`,
   `NSURLErrorDomain`). Either one can have an added delay.
2. **A response only.**

Recommended: 1. Errors and slow calls are the cases a developer most wants
to try.

### D. Which captures serve mocks

1. **OkHttp, Ktor and `URLSession` data and upload tasks.** OkHttp
   short-circuits in its application interceptor. Ktor returns a call
   built with the `@InternalAPI` constructor from the `Send` hook; a DEBT
   records the internal API, and its trigger is a Ktor upgrade.
   `URLSession` adds one `URLProtocol` class at the front of
   `protocolClasses`, in every configuration the app creates after
   `start` (the same session rule as SDD 07). Not served: background
   sessions (Apple's note above), WebSocket upgrades, the gRPC adapter,
   connection metadata and the proxy.
2. **The Kotlin SDKs only.** iOS waits for a case.

Recommended: 1, so the window's rule acts the same on both platforms.

### E. How a mocked exchange shows

1. **Captured as usual, plus a new field `RequestStarted.mock_rule_id`.**
   The exchange keeps its capture layer. The desktop lists it with a
   "mocked" mark and the rule's name, and its timing is the mock's delay.
2. **Not captured.** A developer would then not see in the window what the
   app received.

Recommended: 1.

### F. Who makes rules

1. **The window, plus an MCP tool whose rules start pending.** The window
   creates a rule from an exchange ("Mock this", which fills the method,
   host, path and the response) or from an empty form. Rules are kept per
   app id in the desktop's store, so a restart keeps them. An MCP
   `create_mock` creates a rule that is pending. It reaches no device
   until the owner confirms it in the window (ADR-0005).
2. **The window only.** An agent then cannot set up a scenario, even with
   a confirmation.
3. **MCP rules active at once.** This breaks ADR-0005.

Recommended: 1.

### G. The SDKs' public API

1. **None.** The SDK lists the `interaction` channel whenever it runs.
   Running means a debug build (ADR-0072) whose device was approved
   (ADR-0083). An opt-out option waits for a case.
2. **An option, `interaction = true` by default**, in `AppMonitorOptions`
   and the Swift `AppMonitor.Options`. It would be one more public member
   in every SDK, and in every no-op twin (ADR-0072).

Recommended: 1. It adds no setup step (`AGENTS.md`, short setup).

## Decision

A.1, B.1, C.1, D.1, E.1, F.1, G.1 (every recommended option).

Accepted by the agent on the owner's standing instruction of 2026-10-25
(take the recommended options while the owner is away).

## Consequences

- a new channel `interaction` with its messages and fixtures,
  and a storage migration for the rules. Each SDK gains a rule matcher
  that runs one shared vector (`protocol/fixtures/`).
- With D.1, `sdk/ktor` depends on a Ktor `@InternalAPI` (a DEBT whose
  trigger is a Ktor upgrade), and `sdk/ios` registers a `URLProtocol`
  beside its CFNetwork hooks.
- With E.1, `RequestStarted` gets a new field (guidelines 04: a new
  number, never reused).
