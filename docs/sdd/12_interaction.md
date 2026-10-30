# 12 — Interaction channel

The `interaction` channel, where the desktop changes what an app's calls
do (ADR-0110, ADR-0113, ADR-0118, ADR-0120): the mock rules and
breakpoints the desktop keeps and sends, the calls a breakpoint holds, and
the requests it asks a device to send again.
Messages in `protocol/proto/appmonitor/v1/interaction.proto`; the rules
in `desktop/storage` (`Mocks.kt`, `Mock.sq`); the push in `desktop/app`
(`store/MockPush.kt`, started by `TrafficStore`); the outgoing queue in
`desktop/server` (`DeviceSession.send`); the window's Mocks panel in
`desktop/feature/mocks` (`MocksScreen.kt` over `MocksViewModel`, ADR-0114)
and the `create_mock` tool in
`desktop/mcp` (`Mocks.kt`, SDD 10). Breakpoints in `desktop/storage`
(`Breakpoints.kt`, `Breakpoint.sq`), their push in `desktop/app`
(`store/BreakpointPush.kt`), the held calls in `store/HeldCalls.kt` and
the window's Breakpoints panel and held call dialog in
`desktop/feature/breakpoints` (`BreakpointsScreen.kt` over
`BreakpointsViewModel`, ADR-0119). Replays in `store/Replays.kt`, sent from the request
detail in `desktop/feature/traffic` or asked by MCP's `replay_request` and confirmed in
its dialog (ADR-0121). The Kotlin SDKs list the channel, serve the
rules (SDD 04, 05, 08, ADR-0115), send a request again (ADR-0122) and
hold a call at a breakpoint (ADR-0123); the Swift SDK serves the rules
(SDD 06, 07, ADR-0117), sends a task again (ADR-0124) and holds a task at
a breakpoint (ADR-0125).

## How it works

### Negotiation

- A device lists `interaction` in `Hello.channels`; the server accepts it
  beside `core` and `network` and returns it in `HelloAck.channels`.
- `DeviceSession.send(envelope)` queues an envelope for the device. It is
  refused (`false`) on a channel not in `HelloAck` or once the session
  closed. The server writes the queue in order, only after `HelloAck`,
  so an envelope queued as the session is published still comes second.
- The headless server (`make server`) accepts the channel and sends on it
  only `--mocks FILE`'s rules and, with `--replay PATH`, a `ReplayRequest`
  for each session's first request to that URL path (ADR-0122 D.1); a
  call its device holds goes on at its timeout.

### Messages

| Type | Way | Payload | When |
| --- | --- | --- | --- |
| `MockRules` | desktop → device | every active rule of the device's app, in match order | right after `HelloAck`, even empty; again each time that list changes |
| `BreakpointRules` | desktop → device | every enabled breakpoint of the device's app, in match order | as `MockRules`, independently of it: the two come in no set order |
| `BreakpointHit` | device → desktop | `rule_id`, `stage` (request or response), `request` (a `RequestStarted`), `response` (a `ResponseReceived`, response stage only), `timeout_ms` | a call waits at a breakpoint |
| `BreakpointResume` | desktop → device | `request_id`, `stage`, and only what changed: `method`, `url`, `status_code`, `body` (each absent when unchanged), `set_headers`, `removed_headers` | the developer lets the call go on |
| `BreakpointAbort` | desktop → device | `request_id`, `stage`, `message` | the developer fails the call, as a `MockFailure` |
| `ReplayRequest` | desktop → device | `request_id` of the session's request to send again | "Send again" in the window |
| `ReplayRefused` | device → desktop | `request_id`, `reason` shown to the developer | the device did not send it again (ADR-0120 A.1) |

A `MockRule` carries the desktop's `id`, `method` (empty: any), `host`
(ignoring case), `path` (segment by segment, `*` one segment, the query
not matched), `delay_ms`, and one of `response` (status, headers, body)
or `failure` (a message). Each `MockRules` replaces the one before it.
The fixture is `protocol/fixtures/mock_rules.bin`, mirrored in
`sdk/ios/Tests/AppMonitorTests/Samples.swift`.

A `BreakpointRule` carries `id`, `method`, `host` and `path` matched as a
`MockRule`'s, `request` and `response` (what it holds, at least one) and
`timeout_ms`. A held call is named by its request id and stage (ADR-0118
B.1). A hit the device never writes (over `MAX_FRAME_BYTES`, dropped from
its offline buffer, or discarded at stop) lets its call go on unchanged at
once, not at its timeout. In a resume, every header of a name in `set_headers` is replaced by
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
| Window, Breakpoints panel ("New breakpoint", one opened, "Turn on" / "Turn off", "Delete") | breakpoints: create, edit, delete | `createBreakpoint`, `updateBreakpoint`, `deleteBreakpoint` |
| MCP `create_mock` (SDD 10) | create, pending | `createMock(…, MCP)` |
| "Clear all data" | deletes every rule | SDD 09 |

An edit in the window never confirms a pending rule; only "Confirm"
does. The editor sends the name and the whole rule; "Turn on" and
"Turn off" send `enabled` only.

### The window (ADR-0114)

- "Mocks" sits under the devices; it reads "Mocks (N to confirm)" while
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

### Breakpoints in the window (ADR-0119)

- "Breakpoints" sits beside "Mocks"; it reads "Breakpoints (N held)"
  while N calls are held. The panel lists every app's breakpoints in
  match order, each as `<app> · <method or Any> <host><path> · holds the
  <request, response, or request and response> · <timeout>`, "Off" under
  a disabled one, with "Turn on" / "Turn off". A breakpoint opened, or
  "New breakpoint" (for the first known app), shows the form: name, app
  (fixed once made), method, host, path, "Hold the request", "Hold the
  response", and a timeout in whole seconds, 1 to 600, 60 by default. It
  says what is wrong before "Save" is enabled, as `Storage` checks.
- A held call opens a dialog on its own, the oldest first, with "N more
  held" under it: "Request held" or "Response held", the app, the
  original method and URL, and the time it goes on unchanged. It edits
  the held side: method and URL at the request stage, the status at the
  response stage, headers one `Name: value` per line, the body as UTF-8
  text, or a binary body kept or cleared. A body the device cut says so.
  "Resume" sends `resumeOf` of the form, so an untouched form resumes
  unchanged and a `██` header left alone keeps the device's value;
  "Abort" sends the message in its field ("Aborted from App Monitor").
  The dialog goes when its call does: resumed, aborted, timed out or its
  session ended.

### Replay (ADR-0111 D.1, ADR-0120)

The device sends the request again through the app's own client and
captures the new call as usual, `RequestStarted.replay_of` set to the
original's request id (fixture `replay_started.bin`); in the Kotlin SDKs and
the Swift one, from the last 200 requests of the session (SDD 04, 05, 07, 08, ADR-0122,
ADR-0124). The store keeps it
in `exchanges.replay_of` (SDD 09); `ExchangeDetail.replayOfId` is the
original's row in the same session, null once it is gone.

`TrafficStore.replays` (`Replays`) knows each connected session, from its
first envelope to its end.

| Event | What `Replays` does |
| --- | --- |
| `send(sessionId, requestId)` | queues `ReplayRequest` and answers `SENT`; `SESSION_ENDED` when the session is over, `NOT_TAKEN` when the device did not accept `interaction`. Clears that request's refusal |
| a `ReplayRefused` from a connected session | keeps its reason in `refused`, by session and request id |
| `ask(replay)` (`ReplayAsks`, from `replay_request`) | keeps it in `asked`, oldest first, and answers `WAITING`; the same exchange again is the same one. `SESSION_ENDED`, `NOT_TAKEN` as `send`, `TOO_MANY` past 20 waiting (ADR-0121) |
| `answer(replay, send)` | drops it from `asked`; `send` true goes through `send` |
| the session ends | forgets the session, its refusals and its asked replays |

In the window, a request's detail has "Send again" beside "Mock this"
(not for a WebSocket, a connection or the desktop's proxy). Its label reads
"Send again: runs POST on the server again" for any method but `GET`,
`HEAD` and `OPTIONS`. Under the summary it says what happened: "Sent to
the app; its call shows as a replay", "Not sent: the app's session ended,
and a replay goes through the app", "Not sent: this app's SDK does not
take replays", or "The app did not send it again: <reason>". A replay's
row is marked `replay`; its detail says "Replay of an earlier request"
with "Show original", which clears the filter and opens it, or "Replay of
a request no longer stored".

A replay an agent asked (SDD 10) opens a dialog on its own, the oldest
first, as an approval and a held call (ADR-0121 B.1): "An agent asks to
send this request again", the method and URL, the device, app and
exchange id, how many more wait, and "Refuse" or the same "Send again"
label. Nothing is sent before "Send again" (ADR-0005).

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
| The replay messages round-trip, fixture bytes match on Kotlin and Swift | `RoundTripTest.replayMessages`, `FixtureTest`, Swift `FixtureTests` |
| `replay_of` kept in every list reader, linked only within its session; a version-6 file migrates | `ReplayedExchangeTest` in `desktop/storage` |
| "Send again" reaches a stand-in device; its replay marked, linked to the original; a refusal shown | `ReplayTest` in `desktop/app` (`replay.png`) |
| No replay to a device without `interaction` or to an ended session; a new send clears a refusal | `ReplayTest` |
| A device sends a kept request again and refuses an unknown one, with a module's reason; forgets them when the session ends | `MonitorClientReplayTest` in `sdk/core` |
| OkHttp and Ktor send a call again through the app's client, every interceptor or plugin once, marked `replay_of`; a body sent only once refused | `ReplayTest` in `sdk/android-okhttp` and `sdk/ktor` |
| A real OkHttp call on an emulator sent again on the headless server's `ReplayRequest` | `make e2e-android` |
| `URLSession` sends a task again through its session, its delegate left out, marked `replay_of`; a streamed body and an invalidated session refused; an unknown request refused on the wire | `ReplayTests` in `sdk/ios` |
| A real `URLSession` task on a simulator sent again on the headless server's `ReplayRequest` | `make e2e-ios` |
| The headless server's replay path read, the first request asked, not a replay; `replay_of` and a refusal logged | `MainTest` in `desktop/server` |
| A replay asked over MCP reaches a stand-in device only after "Send again" in its dialog, not after "Refuse" | `ReplayTest` (`replay-asked.png`) |
| An asked replay waits once, up to 20, not for an ended session or a device without `interaction`, and goes with its session | `ReplayTest` |
| A device keeps the last breakpoints, waits on a hit until its own resume or abort, its timeout or the session's end; a hit over a frame or dropped unwritten goes on unchanged at once | `MonitorClientBreakpointsTest`, `EventBufferTest` in `sdk/core` |
| OkHttp and Ktor hold a request and a response, mocked or not, resumed edited (a masked header left alone keeps its value) or unchanged, aborted, an edit refused, an OkHttp call cancelled while held | `BreakpointTest` in `sdk/android-okhttp` and `sdk/ktor` |
| The Swift SDK keeps the last breakpoints, waits on a hit until its resume or abort, its timeout or the session's end; a hit over a frame or dropped unwritten goes on unchanged at once | `BreakpointsTests`, `MonitorClientBreakpointsTests`, `EventBufferTests` in `sdk/ios` |
| `URLSession` holds a data or upload task's request and response, mocked or not, resumed edited (a masked header left alone keeps its value) or unchanged, aborted, an edit refused, a held task cancelled; its delegate gets the data | `BreakpointTests` in `sdk/ios` (macOS host, DEBT-0086) |
| A breakpoint made, turned off and on in the window reaches a stand-in device each time; its held request resumed edited from the dialog (URL and body only sent back), a held response aborted | `BreakpointsUiTest` in `desktop/app` (`breakpoint.png`) |

## Limits

- A rule or a breakpoint cannot be moved in its app's match order
  (DEBT-0082).
- No MCP tool lists, edits, confirms or deletes a rule (ADR-0114 C.1).
- The headless server sends its file's rules once per session; it does not
  reread the file.
- The Swift SDK holds no background task, nor one with a streamed body
  (ADR-0125).
- The held call's dialog covers the window while the call waits
  (ADR-0119).
