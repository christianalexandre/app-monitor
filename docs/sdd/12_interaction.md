# 12 — Interaction channel

The `interaction` channel, where the desktop changes what an app's calls
do (ADR-0110, ADR-0113, ADR-0118): the mock rules and breakpoints the
desktop keeps and sends, and the calls a breakpoint holds.
Messages in `protocol/proto/appmonitor/v1/interaction.proto`; the rules
in `desktop/storage` (`Mocks.kt`, `Mock.sq`); the push in `desktop/app`
(`store/MockPush.kt`, started by `TrafficStore`); the outgoing queue in
`desktop/server` (`DeviceSession.send`); the window's Mocks panel in
`desktop/app` (`ui/Mocks.kt`, ADR-0114) and the `create_mock` tool in
`desktop/mcp` (`Mocks.kt`, SDD 10). Breakpoints in `desktop/storage`
(`Breakpoints.kt`, `Breakpoint.sq`), their push in `desktop/app`
(`store/BreakpointPush.kt`) and the held calls in `store/HeldCalls.kt`;
no window and no SDK holds a call yet (PLAN-0010). The Kotlin SDKs list the channel and
serve the rules (SDD 04, 05, 08, ADR-0115), and so does the Swift SDK
(SDD 06, 07, ADR-0117).

## How it works

### Negotiation

- A device lists `interaction` in `Hello.channels`; the server accepts it
  beside `core` and `network` and returns it in `HelloAck.channels`.
- `DeviceSession.send(envelope)` queues an envelope for the device. It is
  refused (`false`) on a channel not in `HelloAck` or once the session
  closed. The server writes the queue in order, only after `HelloAck`,
  so an envelope queued as the session is published still comes second.
- The headless server (`make server`) accepts the channel and sends on it
  only `--mocks FILE`'s rules; a call its device holds goes on at its
  timeout.

### Messages

| Type | Way | Payload | When |
| --- | --- | --- | --- |
| `MockRules` | desktop → device | every active rule of the device's app, in match order | right after `HelloAck`, even empty; again each time that list changes |
| `BreakpointRules` | desktop → device | every enabled breakpoint of the device's app, in match order | as `MockRules`, independently of it: the two come in no set order |
| `BreakpointHit` | device → desktop | `rule_id`, `stage` (request or response), `request` (a `RequestStarted`), `response` (a `ResponseReceived`, response stage only), `timeout_ms` | a call waits at a breakpoint |
| `BreakpointResume` | desktop → device | `request_id`, `stage`, and only what changed: `method`, `url`, `status_code`, `body` (each absent when unchanged), `set_headers`, `removed_headers` | the developer lets the call go on |
| `BreakpointAbort` | desktop → device | `request_id`, `stage`, `message` | the developer fails the call, as a `MockFailure` |

A `MockRule` carries the desktop's `id`, `method` (empty: any), `host`
(ignoring case), `path` (segment by segment, `*` one segment, the query
not matched), `delay_ms`, and one of `response` (status, headers, body)
or `failure` (a message). Each `MockRules` replaces the one before it.
The fixture is `protocol/fixtures/mock_rules.bin`, mirrored in
`sdk/ios/Tests/AppMonitorTests/Samples.swift`.

A `BreakpointRule` carries `id`, `method`, `host` and `path` matched as a
`MockRule`'s, `request` and `response` (what it holds, at least one) and
`timeout_ms`. A held call is named by its request id and stage (ADR-0118
B.1). In a resume, every header of a name in `set_headers` is replaced by
that list's headers of the name, and every header named in
`removed_headers` (ignoring case) goes; a header left alone, a masked
`██` one included, is in neither and keeps the device's value (ADR-0111
C.1). The fixtures are `protocol/fixtures/breakpoint_rules.bin`,
`breakpoint_hit.bin`, `breakpoint_resume.bin` and `breakpoint_abort.bin`.

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

### Breakpoints on the desktop

- Kept per app id in `breakpoint_rules` (SDD 09), in match order, a new
  one last and on, ids never reused. No pending state: breakpoints are
  made in the window only (ADR-0111 E.1).
- `Storage.createBreakpoint(appId, name, rule)`, `updateBreakpoint(id,
  change)` (name, enabled, rule), `deleteBreakpoint(id)`, `breakpoints()`,
  `observeBreakpoints()` and `observeActiveBreakpoints(appId)`, which
  emits the enabled list again only when it changes; writes return a
  `BreakpointWrite`.
- Refused at the call: a blank name or host, a path not starting with
  `/`, neither `request` nor `response`, a timeout outside 1 ms to
  `MAX_BREAKPOINT_TIMEOUT_MS` (10 minutes, ADR-0118 E.1).
- "Clear all data" deletes every breakpoint.

### Held calls

`TrafficStore.heldCalls` (`HeldCalls`) holds, in memory, each well-formed
`BreakpointHit` a session sends: a request id, a known stage, and a
response at the response stage. A newer hit for the same request and stage
replaces the older one. The envelope is still stored raw (SDD 09).

| Event | What `HeldCalls` does |
| --- | --- |
| `resume(call, edit)` | sends `resumeOf(call, edit)`: the fields of `edit` that differ from `call.unedited` (method and URL at the request stage, status at the response stage, headers by name, the body); false once the call is gone. A body over `Protocol.MAX_BODY_BYTES` is refused |
| `abort(call, message)` | sends `BreakpointAbort`; false once the call is gone |
| the hit's `timeout_ms` passes, from its arrival | drops the call: the device went on unchanged |
| the session ends | drops every call of it |

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

### A mocked exchange

The SDK captures a mocked call as usual, with `RequestStarted.mock_rule_id`
set (ADR-0110 E.1). The store keeps the id (SDD 09); the request list marks
the row `mocked`, the detail's summary line ends `Mocked by rule "<name>"`,
or `Mocked by deleted rule <id>` once the rule is gone, and MCP rows carry
`mock_rule_id`, `get_request` also `mock_rule_name` (SDD 10, ADR-0116).

### The headless server's rules

`make server ARGS="--mocks FILE"` reads a JSON file of rules, named as in
`interaction.proto` (`id`, `method`, `host`, `path`, `delay_ms`, and
`response` with `status_code`, `headers` and a UTF-8 `body`, or `failure`
with `message`), and sends the set once to every session that accepted
`interaction`, logging `sent N mock rules`. A mocked `RequestStarted` is
logged with ` mock <id>` at the end. Code: `desktop/server` (`MockFile.kt`,
`Main.kt`); `make e2e-android` uses it (SDD 02, ADR-0116).

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
| Which rule answers a request, one vector for every SDK | `protocol/fixtures/mock_matching.json`, `MockMatchingVectorTest` in `sdk/core` |
| A device keeps the last set, empties it when the session ends | `MonitorClientMocksTest` in `sdk/core` |
| OkHttp and Ktor answer a matching call without the network | `MockAnswerTest` in `sdk/android-okhttp` and `sdk/ktor` |
| The Swift SDK runs the vector, keeps the last set, empties it when the session ends | `MockMatchingVectorTests`, `MonitorClientMocksTests` in `sdk/ios` |
| `URLSession` answers a matching data or upload task without the network, after its delay, a failure and a cancel included | `MockAnswerTests` in `sdk/ios` |
| A rule answers a call on a real emulator and simulator, end to end | `make e2e-android`, `make e2e-ios` |
| A mocked exchange stored with its rule, marked in the list, named in the detail and `get_request` | `MockedExchangeTest` in `desktop/storage`, `MockedExchangeUiTest` (`mocked.png`), `GetRequestTest` |
| The headless server's rules file read, bad ones refused, a mocked request logged with its rule | `MainTest` in `desktop/server` |
| A real OkHttp call on an emulator answered by a rule the server sent | `make e2e-android` |
| The breakpoint messages round-trip, fixture bytes match on Kotlin and Swift; an absent resume field kept apart from an empty one | `RoundTripTest.breakpointMessages`, `RoundTripTest.aResumeKeepsAnUnchangedFieldApartFromAnEmptyOne`, `FixtureTest`, Swift `FixtureTests` |
| Breakpoints per app in order, enabled only, a new list only on a change, kept, cleared, refused | `BreakpointsTest` in `desktop/storage` |
| A stand-in device gets its breakpoints after `HelloAck` and on a change; its hit held, resumed with only the changed fields (the masked header not sent back), aborted, let go at its timeout and when its session ends; a malformed hit not held | `HeldCallsTest` in `desktop/app`, against a real server |
| A response resume's headers set and removed by name; a truncated body left alone not sent | `HeldCallsTest` |

## Limits

- A rule cannot be moved in its app's match order (DEBT-0082).
- No MCP tool lists, edits, confirms or deletes a rule (ADR-0114 C.1).
- The headless server sends its file's rules once per session; it does not
  reread the file.
- No window shows a held call or a breakpoint yet, and no SDK holds a
  call: both read `BreakpointRules` as an envelope they drop (PLAN-0010
  M9.8, M9.10, M9.11).
