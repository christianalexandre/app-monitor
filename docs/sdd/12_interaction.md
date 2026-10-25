# 12 — Interaction channel

The `interaction` channel, where the desktop changes what an app's calls
do (ADR-0110, ADR-0113): today the mock rules the desktop keeps and sends.
Messages in `protocol/proto/appmonitor/v1/interaction.proto`; the rules
in `desktop/storage` (`Mocks.kt`, `Mock.sq`); the push in `desktop/app`
(`store/MockPush.kt`, started by `TrafficStore`); the outgoing queue in
`desktop/server` (`DeviceSession.send`); the window's Mocks panel in
`desktop/app` (`ui/Mocks.kt`, ADR-0114) and the `create_mock` tool in
`desktop/mcp` (`Mocks.kt`, SDD 10). No SDK lists the channel yet
(PLAN-0010), so no device is sent a rule.

## How it works

### Negotiation

- A device lists `interaction` in `Hello.channels`; the server accepts it
  beside `core` and `network` and returns it in `HelloAck.channels`.
- `DeviceSession.send(envelope)` queues an envelope for the device. It is
  refused (`false`) on a channel not in `HelloAck` or once the session
  closed. The server writes the queue in order, only after `HelloAck`,
  so an envelope queued as the session is published still comes second.
- The headless server (`make server`) accepts the channel and sends
  nothing on it.

### Messages, desktop to device

| Type | Payload | When |
| --- | --- | --- |
| `MockRules` | every active rule of the device's app, in match order | right after `HelloAck`, even empty; again each time that list changes |

A `MockRule` carries the desktop's `id`, `method` (empty: any), `host`
(ignoring case), `path` (segment by segment, `*` one segment, the query
not matched), `delay_ms`, and one of `response` (status, headers, body)
or `failure` (a message). Each `MockRules` replaces the one before it.
The fixture is `protocol/fixtures/mock_rules.bin`, mirrored in
`sdk/ios/Tests/AppMonitorTests/Samples.swift`.

### Rules on the desktop

- Kept per app id in `mock_rules` (SDD 09), in match order by `position`;
  a new rule goes last. Ids are never reused.
- `Storage.createMock(appId, name, rule, source)`, `updateMock(id,
  change)` (name, enabled, rule), `confirmMock(id)`, `deleteMock(id)`,
  `mocks()` and `observeMocks()`; writes go through the queue and return
  a `MockWrite`.
- A rule from `MockSource.MCP` starts pending; one from the window does
  not. A rule is **active** when enabled and not pending, and only active
  rules are sent (ADR-0005, ADR-0110 F.1).
- Refused at the call: a blank name or host, a path not starting with
  `/`, neither or both answers, a negative delay, a body over
  `Protocol.MAX_BODY_BYTES`.
- `observeActiveMocks(appId)` emits the active list, then again only when
  it changes: a rename, another app's rule or a pending one sends
  nothing.

### Who writes rules

| Writer | Writes | How |
| --- | --- | --- |
| Window, Mocks panel ("New rule", a rule opened, "Turn on" / "Turn off", "Confirm", "Delete") | create, edit, confirm, delete | `createMock(…, WINDOW)`, `updateMock`, `confirmMock`, `deleteMock` |
| Window, "Mock this" on a request | create, after the editor's "Save" | `createMock(…, WINDOW)` |
| MCP `create_mock` (SDD 10) | create, pending | `createMock(…, MCP)` |
| "Clear all data" | deletes every rule | SDD 09 |

An edit in the window never confirms a pending rule; only "Confirm"
does. The editor sends the name and the whole rule; "Turn on" and
"Turn off" send `enabled` only.

### The window (ADR-0114)

- "Mocks" sits under the run picker; it reads "Mocks (N to confirm)" while
  N rules wait for a confirmation. The panel lists every app's rules,
  grouped by app id in match order, each as `<app> · <method or Any>
  <host><path> → <status, or fails: message>[ · delay]`, then its state:
  "From MCP: not sent until you confirm it" with "Confirm", "Off", or
  "Not sent: past one frame, with the rules before it" for an active
  rule `fittingRules` (the push's own cut) leaves out.
- "New rule" opens an empty form for the first known app (any session,
  any run); a rule's app is fixed once made.
- "Mock this", in a request's detail (not for a WebSocket or a
  connection), opens a new rule filled from the exchange: the session's
  app id, method, host and path without the query, and the response's
  status, headers and stored body; a failed exchange without a response
  fills a failure with its error. `Content-Length`, `Transfer-Encoding`
  and `Content-Encoding` are left out, and a body still gzip (as
  `sdk/ktor` stores it, SDD 08) is gunzipped (ADR-0114 B.1). A body that is not
  UTF-8 shows as "Binary body, N bytes, kept as captured" and can only be
  cleared.
- The form says what is wrong before "Save" is enabled (a name, an app, a
  host, a path from `/`, a delay and a status 100–599 as numbers, a header
  line `Name: value`, a body within `Protocol.MAX_BODY_BYTES`), the same
  checks `Storage` makes.

### The push

`TrafficStore` starts `pushMocks` for each session that accepted
`interaction`, and cancels it when the session ends. Each list
`observeActiveMocks` emits becomes one `MockRules` envelope. Rules go in
order while the envelope fits `Protocol.MAX_FRAME_BYTES`; the first that
does not, and every one after it, are left out (ADR-0113 C.1).

### Tests that drive it

| Case | Test |
| --- | --- |
| `MockRules` round-trips, fixture bytes match on Kotlin and Swift | `RoundTripTest.mockRules`, `FixtureTest`, Swift `FixtureTests` |
| An envelope queued before `HelloAck` arrives after it; another channel and a closed session refused | `AppMonitorServerTest` |
| Active rules per app in order, pending until confirmed, disabled and deleted ones gone | `MocksTest.aDeviceIsSentItsAppsActiveRulesInOrder` |
| A new list only on a change of what is sent | `MocksTest.theActiveRulesAreObservedAgainOnlyWhenTheyChange` |
| Kept across a restart and retention, deleted by "Clear all data", ids not reused; a version-3 file migrates | `MocksTest` |
| A rule no SDK can serve refused | `MocksTest.aRuleNoSdkCanServeIsRefused` |
| A stand-in device gets the rules after `HelloAck` and again on a confirm; without the channel, nothing | `MockPushTest` in `desktop/app`, against a real server |
| A set past one frame cut at the first rule that does not fit | `MockPushTest` |
| "Mock this" on a stored exchange, edited and saved, reaches a connected stand-in device as one `MockRules`; framing and encoding headers left out | `MocksUiTest` in `desktop/app` |
| An MCP rule pending, counted on the button, confirmed in the panel; an active rule past one frame marked | `MocksUiTest` (`mocks.png`) |
| `create_mock` stores a pending rule no device gets until confirmed; its argument errors | `MocksTest` in `desktop/mcp` |

## Limits

- A rule cannot be moved in its app's match order (DEBT-0082).
- No MCP tool lists, edits, confirms or deletes a rule (ADR-0114 C.1).
- No SDK serves a rule yet (PLAN-0010).
