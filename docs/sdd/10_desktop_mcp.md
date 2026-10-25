# 10 — Desktop MCP server

`desktop/mcp` serves the stored traffic to AI agents over MCP from inside
the desktop process (ADR-0005), read-only but for issues, mock rules and replays asked. Decisions:
ADR-0089 (what the tools return), ADR-0090 (who can call, port, on by
default), ADR-0091 (clients), ADR-0092 (SDK, transport, modules), ADR-0095
and ADR-0096 (issues), ADR-0114 (`create_mock`, rules left pending, ADR-0110 F.1), ADR-0121 (`replay_request`). Code: `desktop/mcp/`, used by
`desktop/app`; every tool, the token and the store's health go through
`desktop/domain`'s repositories `desktop/app` hands it (`McpRepositories`,
`SettingRepository`, ADR-0133, ADR-0138), so `desktop/mcp`'s main code
does not depend on `desktop/storage` (SDD 09); it writes only
through `save_issue`, `create_mock` and the two settings below; `replay_request` leaves its replay to `desktop/app`. Client setup: the README's *Ask your AI
agent (MCP)*; the live check against a sample's traffic: SDD 02.

## How it works

### Process and endpoint

```
claude / Inspector / any client ──HTTP POST /mcp──► 127.0.0.1:47475  (Ktor CIO, own listener)
                                                     │ 1. Host and Origin, exact, with the port  → 403
                                                     │ 2. Authorization: Bearer <token>          → 401
                                                     │ 3. SDK DnsRebindingProtection (kept on)
                                                     ▼
                                    mcpStatelessStreamableHttp: one session per request
                                                     │ tools/list, tools/call
                                                     ▼
                                    repository reads (Dispatchers.IO, one transaction each);
                                    save_issue: IssueRepository create / update on the writer queue
```

- `McpServer(repositories, access, port)` builds one SDK `Server` with every
  tool, and an `embeddedServer(CIO, host = "127.0.0.1", port)`;
  `start()` binds and returns the port, `stop()` stops it. The port is
  `47475`, or `APPMONITOR_MCP_PORT` when set to a number (ADR-0090 D.1);
  tests pass `0`. A taken port is a `java.net.BindException`: `start` first
  binds and closes a plain `ServerSocket` on it, because CIO reports a
  taken port as a cancellation caused by one **and** as an uncaught
  exception on `Dispatchers.IO`; a port taken between the two still
  throws the cause CIO reports. `start`
  and `stop` hold one lock: a `stop` during `start` waits for it, then
  frees the port; the server is single use.
- Stateless (ADR-0092): no `Mcp-Session-Id`, `GET` and `DELETE` answer
  `405`, each `POST` is one JSON response. Server capabilities: `tools`
  only, `listChanged = false`. Every tool is annotated `readOnlyHint: true`
  but `save_issue` and `create_mock`: `readOnlyHint`, `destructiveHint` and
  `idempotentHint` false (they create and edit, never delete, ADR-0095
  C.1, ADR-0114 C.1), and `replay_request`: `readOnlyHint` and
  `idempotentHint` false, `destructiveHint` and `openWorldHint` true (a
  confirmed `POST` runs again on a server, ADR-0121 C.1).
- Steps 1 and 2 are one Ktor application plugin (`accessCheck`), before the
  SDK's routing; `<port>` is the local port the connection reached. Step 1:
  exactly one `Host`, `127.0.0.1:<port>` or
  `localhost:<port>`; an `Origin`, when present, `http://127.0.0.1:<port>`
  or `http://localhost:<port>`; no `Origin` passes (ADR-0090 A.1). Step 2:
  `Authorization` must be `Bearer <token>`, compared in constant time
  (`MessageDigest.isEqual`); else `401` with `WWW-Authenticate: Bearer`.
  Neither answer says which part failed beyond the status.
- `make server` does not serve MCP: it has no store.

### Token and switch

`McpAccess` keeps two keys in the store's `settings` table (SDD 09):
`mcp.token` and `mcp.enabled` (`true` or `false`, missing = `true`,
ADR-0090 C.1).

- At launch, after `Storage.open`: no token → 32 bytes from `SecureRandom`,
  base64url without padding, written through the queue (`setSetting`).
- `token` is a `StateFlow` the plugin reads on each request, so
  "Regenerate token" and "Clear all data" (which deletes both keys,
  ADR-0088) take effect without a restart: after `clearAll()` the app
  calls `McpController.afterClearAll()`, which regenerates the token and
  writes `mcp.enabled = false` back when the switch was off, so clearing
  data never turns on a server the developer turned off.
- `McpAccess.open(settings)` reads or makes the token through a
  `SettingRepository`, `regenerate()`
  replaces it in memory at once and in the store through the queue.
- The settings' "MCP server" section shows the switch, the state
  ("Listening on http://127.0.0.1:<port>/mcp", "Port <port> in use: quit
  what holds it, or set APPMONITOR_MCP_PORT", "Off", "Failed: <message>"),
  and, when on, the command to paste with "Copy command" and "Regenerate
  token":
  `claude mcp add --transport http app-monitor http://127.0.0.1:<port>/mcp --header "Authorization: Bearer <token>"`
  (`claudeMcpAdd` in `ui/App.kt`).
- `McpController` (`desktop/app`, `store/McpController.kt`) owns the
  switch: `start()` reads `mcp.enabled` and starts when on, unless a
  `setEnabled` came first, which wins; `start`'s check and write of the
  switch and `setEnabled`'s write share one lock, so a `setEnabled` that
  lands as `start` writes is never overwritten; `setEnabled`
  writes it and starts or stops; each start builds a new `McpServer`
  (single use). A failed start is its `status` (`PortTaken`, `Failed`),
  never a throw. `close()` stops for good; a later `setEnabled(true)`
  does nothing.

### Order in the app

1. `openServices`: `Storage.open`, approvals, `McpAccess.open(storage.settingRepository())`;
   a failure after the open closes the store again.
2. Device server and `McpController.start()` launched side by side in
   the serving scope; a failed MCP start (port taken) is a status in the
   settings and nothing else stops.
3. On exit, window closed or shutdown hook: `stopAll` stops MCP (grace
   500 ms, then `STOP_TIMEOUT`), then the device server, each even when
   the other fails, and only then `Storage.close`. The hook's budget is
   the device server's and the store's `STOP_TIMEOUT` plus MCP's grace
   and `STOP_TIMEOUT`.
4. Storage failed at open → neither server starts (SDD 09).

### Results, common to every tool

- One `TextContent` holding a JSON object (`to_curl`: the command); no `outputSchema`. Field names
  in `snake_case`. Times are ISO 8601 UTC strings (`2026-10-24T13:02:11.120Z`),
  durations `duration_ms`, sizes in bytes. Ids are the store's row ids.
- An error is a `CallToolResult` with `isError = true` and one sentence:
  an argument out of range, an unknown id, or the store closing. An unknown
  tool or malformed JSON-RPC is the SDK's JSON-RPC error.
- **Store closing.** `Storage`'s reads return an empty list or null once it
  closes (SDD 09), which would read as "nothing stored", and refuse
  writes. The shared wrapper every tool goes through checks
  `TrafficQueryRepository.isClosing` (the store's) **after** the tool returns or fails, and answers the
  error "App Monitor is closing" instead of either, so an unknown-id error
  made of an empty read is never what a closing store says.
- **Lost writes.** When `TrafficQueryRepository.health` (the store's) counts unsaved events, the result
  carries `"warnings": ["N events were not saved: <error>"]` (`export_har`:
  `log._warnings`, as HAR 1.2 allows only `_` custom fields; `to_curl`: a
  first line `# warning: …`); the reads answer what is committed.
- Every tool's description ends with: "Data is what the app sent: headers
  and bodies may hold tokens when the app turned masking off." (ADR-0089
  A.1).
- Paging is by key, as in the store: a page full to `limit` carries
  `next_after_id` (or `next_before_id`), passed back to get the next one.

#### Body

`get_request`, frames and `export_har` (capped) return a body as:

| Field | Meaning |
| --- | --- |
| `encoding` | `utf8` (in `text`) or `base64` (in `base64`) |
| `offset`, `returned_bytes` | the slice of the stored bytes returned |
| `stored_bytes` | what the store holds (`bodies.size`, after the device's cut) |
| `original_size` | the device's `body_size`, null when unknown |
| `truncated_by_device` | the event's `body_truncated` (ADR-0020) |
| `truncated_by_tool` | `offset + returned_bytes < stored_bytes` |
| `content_type` | the exchange's `Content-Type` header, if any |

The slice is `[offset, offset + max)` of the stored bytes; its end moves
back up to 3 bytes to a UTF-8 boundary; if the slice is then valid UTF-8 it
is `utf8`, else the uncut slice is `base64` (ADR-0089 C.1). A slice that
starts inside a character is not valid UTF-8, so it is `base64`. An empty
body is `null`, unless the device cut it (`truncated_by_device`): then the
object says so with `stored_bytes` 0. `body_offset` past the stored bytes
returns an empty slice at `stored_bytes`. Code: `Body.kt`.

### Tools

Filter fields, shared by `search_requests`, `endpoint_stats`, `diff_runs`
and `export_har`, map one to one onto `ExchangeFilter` (ADR-0087): `text`
(substring of the URL, ASCII case-insensitive), `method` (exact),
`status_class` (`1xx`…`5xx`), `failed_only`, `device_id`, `app_id`,
`run_id`, `session_id`. Unset fields match all; without `run_id` or a
stored `session_id` a tool reads the live list, as do `get_request`,
`to_curl` and `replay_request` given a live id (ADR-0141 C). Code: `Filters.kt`, which
also writes the exchange row both `search_requests` and `get_request`
return (`capture_layer` as `platform`, `adapter`, `connection`, `proxy`,
`unspecified`; `duration_ms` null unless both times are known). A
connection (ADR-0104) has `status_code` null, `failed` when it was
refused or reset. `grpc_status` is the last `grpc-status` response header
as a number, null when there is none (ADR-0107). `mock_rule_id` is the
rule that answered the exchange on the device, null when the network did
(ADR-0110 E.1); `get_request` adds `mock_rule_name`, null once the rule is
deleted (ADR-0116). `replay` is true for a call the app sent again
(ADR-0111 D.1); `replay_of` is the original's exchange id in the same
session, null when it is not a replay or the original is gone
(`replayOriginals`, `ExchangeDetail.replayOfId`, ADR-0121 D.1).

| Tool | Input | Output | Store |
| --- | --- | --- | --- |
| `list_runs` | `limit` (1–100, 20), `before_id` | `runs[]`: `id`, `name`, `started_at`, `ended_at`, `current`, `saved` (the "Saved" collection, ADR-0143), `exchange_count`, `sessions[]` (`session_id`, `device_id`, `device_name`, `platform` (`android`, `ios`, `jvm`, `unspecified`), `os_version`, `app_id`, `app_version`, `sdk_name`, `connected_at`, `disconnected_at`); `next_before_id`, null on a page short of `limit`. Newest first. | `runSummaries(beforeId, limit)`: runs, their sessions and exchange counts in one read |
| `search_requests` | filter fields, `after_id`, `limit` (1–200, 50) | `requests[]`: `id`, `session_id`, `method`, `url`, `status_code`, `grpc_status`, `failed`, `error`, `started_at`, `duration_ms`, `capture_layer`, `frame_count`, `mock_rule_id`, `replay`, `replay_of`; `next_after_id`. Oldest first. No headers, no bodies (ADR-0089 B.1). | `searchExchanges(filter, afterId, limit)` |
| `get_request` | `id` (required), `part` (`request`, `response`, `both`; `both`), `body_offset` (0), `body_max_bytes` (1–262 144, 16 384), `frames_after_id`, `frames_limit` (0–200, 20) | the row of `search_requests`, `mock_rule_name`, `request` (`method`, `url`, `headers[]`, `body`), `response` (`status_code`, `headers[]`, `body`), `frames` (`items[]`: `id`, `at`, `direction`, `opcode`, `payload` as a body; `next_after_id`). `part` leaves the other side `null`; `body_offset` applies to both bodies, frame payloads start at 0. Frames: `direction` `sent`/`received`, `opcode` `text`, `binary`, `ping`, `pong`, `close`. The frames page also stops once its payloads reach `body_max_bytes`, after at least one frame; `next_after_id` is set when it stopped early or is full to `frames_limit`. `connection` (`sent_bytes`, `received_bytes`, each null until it closed) for a connection, which has `request` and `response` null; null for any other exchange. Unknown id: "no exchange with id N". | `exchange(id)`, `frames(id, afterId, limit)` |
| `endpoint_stats` | filter fields, `limit` (1–500, 50) | `endpoints[]` by count, descending, then by key: `method`, `host`, `path`, `count`, `status` (`1xx`…`5xx`, `failed`: counts), `error_rate`, `duration_ms` (`min`, `p50`, `p95`, `p99`, `max`; null when no exchange has both times), `request_bytes`, `response_bytes` (totals; a connection's bytes sent and received, its `path` `:port`); `endpoint_count` (before `limit`), `exchanges_read`, `truncated` | `searchExchangeSizes(filter, afterId, limit)`: the rows with their stored body sizes, 0 when none |
| `diff_runs` | `base_run_id`, `run_id` (required), filter fields, `limit` (1–500, 50, per list) | `base_run_id`, `run_id`; `added[]` (in `run_id` only) and `gone[]` (in `base_run_id` only), as `endpoint_stats`' endpoints; `changed[]` (`method`, `host`, `path`, and `count`, `error_rate`, `p50_ms`, `p95_ms`, each `base`, `run`, `delta`); `added_count`, `gone_count`, `changed_count` (before `limit`); `unchanged` (a count); `truncated` (either run hit the read cap). Unknown run: "no run with id N". | `runs()`, then `endpoint_stats`' grouping once per run |
| `export_har` | filter fields, `max_entries` (1–1 000, 100), `bodies` (`none`, `capped`, `full`; `capped`) | HAR 1.2 JSON as the text; `capped` bodies at 16 KiB each | `searchExchanges`, `exchange(id)`, `frames` |
| `to_curl` | `id` (required) | a POSIX shell command as the text | `exchange(id)` |
| `list_issues` | `status` (`open`, `resolved`), `tag` (exact), `limit` (1–100, 20), `before_id` | `issues[]`: `id`, `title`, `note`, `tags[]` (sorted), `status`, `source` (`window`, `mcp`), `created_at`, `updated_at`, `exchange_ids[]` (ascending); `next_before_id`. Newest first. | `issuePage(filter, beforeId, limit)`: issues and their links in one read |
| `save_issue` | `id` (omit to create), `title`, `note`, `tags[]` (0–50), `status`, `exchange_ids[]` (1–1 000) | `created`, `issue` as in `list_issues` | `createIssue(…, IssueSource.MCP)` or `updateIssue(id, IssueChange)`, then `issuePage` |
| `create_mock` | `app_id`, `name`, `host`, `path` (required), `method`, `delay_ms` (≥ 0, 0); `status_code` (100–599) with `headers[]` (`name`, `value`; 0–100) and `body` (text) or `body_base64`, or `failure_message` | `mock`: `id`, `app_id`, `name`, `enabled`, `pending` (true), `source` (`mcp`), `method` (null: any), `host`, `path`, `delay_ms`, `response` (`status_code`, `headers[]`, `body_bytes`) or null, `failure_message` or null, `created_at`, `updated_at` | `createMock(…, MockSource.MCP)`, then `mocks()` |
| `replay_request` | `id` (required) | `replay`: `exchange_id`, `status` (`waiting`) | `exchange(id)`, `session(id)`, then `ReplayAsks.ask` |

Details the table leaves out:

- **Endpoint** (`endpoint_stats`, `diff_runs`): method, the `host` column
  lower-cased, and the URL's path without query or fragment (`/` when
  empty); the scheme is dropped, so `ws`/`wss` and `http`/`https` group
  together (pays DEBT-0058's grouping half). No path templating:
  `/users/1` and `/users/2` are two endpoints. `error_rate` = (status ≥ 400,
  failed, or `grpc_status` other than 0) / count; an exchange still in flight counts in `count` only.
  Durations over exchanges with both times, percentiles by nearest rank
  (the smallest value with at least p % at or below it); bytes are stored
  body bytes, after the device's cut. The tool reads at most 100 000
  exchanges, oldest first, by page of `PAGE_SIZE`, and groups them in
  Kotlin (`groupByEndpoint` in `desktop/storage`, SDD 09, ADR-0097, which
  `diff_runs` and the window's benchmarks reuse; `EndpointStats.group` adds
  the closing check); `truncated` is true when a matching exchange is left
  past that. Code: `EndpointStats.kt`.
- **Changed** (`diff_runs`): an endpoint in both runs whose count or
  error rate differs, or whose p50 or p95 moved by at least 20 % of the
  base and 10 ms, or is known in one run only (`delta` null); the others
  are counted in `unchanged`. `changed` is sorted by the error rate's
  change, then p95's, in absolute value, largest first, then by key. The
  filter fields apply to both runs; `run_id` is the compared run. Code:
  `diffEndpoints` in `desktop/storage` (SDD 09, ADR-0097), which the
  window's run diff reuses; `DiffRuns.kt` the arguments and the JSON.
- **HAR** (`export_har`): `log.creator` is App Monitor and its version;
  an entry per exchange, oldest first, with `startedDateTime`, `time`,
  `request` (`method`, `url`, `httpVersion` `"unknown"`, `cookies` `[]`
  (the headers carry them), `headers`, `queryString` parsed from the URL
  and URL-decoded, `postData` with `mimeType` and `text` when a body is
  stored, `headersSize` -1, `bodySize` the device's `body_size` or the
  stored bytes), `response` (`status`, 0 without a response, `statusText`
  `""`, `headers`, `content` with `size`, `mimeType`, `text`, `encoding:
  "base64"` for a binary body), `cache` `{}`, `timings` with `send` and
  `receive` 0 and `wait` the duration. HAR has no -1 for `time`, `send`,
  `wait`, `receive`: an exchange without an end time has 0 and the
  `comment` "no end time stored"; a failed one "failed: <error>". A binary
  request body is base64 `text` with `_encoding: "base64"` (`postData` has
  no `encoding`). A body cut by the device or the export gets a `comment`
  saying how much; `bodies: none` writes no `text`. WebSocket frames go in
  `_webSocketMessages` (`type` `send`/`receive`, `time` in epoch seconds,
  `opcode` per RFC 6455: 1 text, 2 binary, 8 close, 9 ping, 10 pong,
  `data`: text, else base64), payloads under the same `bodies` rule. More
  HTTP matches than `max_entries` → `log.comment` says so. Connections
  (ADR-0104) are left out, do not count against `max_entries`, and
  `log.comment` counts them. Output past 32 MiB of
  text (counted entry by entry) is refused with "narrow the filter or use
  bodies: none". `full` is for a developer who saves the file: Claude Code
  writes a result over 50 000 characters to a file (ADR-0089). Code:
  `ExportHar.kt`; `ExportHar.log`, past the arguments, is what the
  window's "Export HAR" calls through `Exports` (ADR-0098).
- **cURL** (`to_curl`): `curl --globoff -X '<method>' '<url>'` (`--head`
  for `HEAD`, which `-X` would leave waiting for a body; curl refuses it
  beside a body, so a `HEAD`'s stored body is left out and a first line says
  `# body of <n> bytes left out: curl sends none with --head`), one
  `-H '<name>: <value>'` per stored header in order but `Content-Length`
  and `Transfer-Encoding`, which curl sets from the body it sends (a stored
  one would not match a body cut by the device; a stored `Content-Length: 0`
  is kept when no body is sent, as curl then sends none; only the first
  zero one, however written), the values as stored
  (masked ones stay masked), an empty value as `-H '<name>;'` (curl drops
  `<name>:`); text bodies as `--data-raw '<body>'`, binary ones, and text
  holding NUL (no shell argument can), as
  `printf %s '<base64>' | base64 -d | curl … --data-binary @-`. Single
  quotes escaped as `'\''`; one argument per line, joined by ` \`. A body
  cut by the device adds a first line
  `# body truncated by the device: <stored> of <original> bytes`. No
  request event stored: "exchange N has no request stored"; a connection
  (ADR-0104): "exchange N is a connection: it has no HTTP request to
  copy". Code:
  `ToCurl.kt`; `ToCurl.command` is also the window's cURL tab
  (`Exports`, ADR-0098).

- **Issues** (`save_issue`, ADR-0095, ADR-0096): without `id` it creates
  an issue with `source` `mcp`; `title` (not blank, trimmed) and
  `exchange_ids` required, `note` empty, `tags` none and `status` `open` by
  default. With `id` each omitted field is unchanged (an `IssueChange`
  null), `tags` and `exchange_ids` replace the stored ones, and an edit
  with no field is refused; so an edit the developer makes in the window
  to another field meanwhile is kept, and on the same field the write the
  writer queue takes last wins (SDD 09 lists every writer). A live
  exchange id is saved to "Saved" first and the issue links the saved row
  (ADR-0143). The call waits for its batch, then reads the issue back. Errors: an exchange not
  stored when the write reaches the writer, "no exchange with id 4, 7",
  nothing written; a deleted or unknown issue, "no issue with id N"; a
  closing store, "App Monitor is closing"; a lost batch, "the issue was not
  saved: <error>", and later calls carry the lost write in `warnings`.
  No tool deletes an issue or changes traffic. Code: `Issues.kt`.
- **Mock rules** (`create_mock`, ADR-0110, ADR-0114): the rule goes last
  in its app's match order and is **pending**: `observeActiveMocks` leaves
  it out, so no device gets it until the developer clicks "Confirm" in
  the window's Mocks panel (SDD 12, ADR-0005). `name`, `app_id` and
  `host` not blank, `path` starting with `/`; exactly one of
  `status_code` and `failure_message`; `headers` and a body only with
  `status_code`; a body over `Protocol.MAX_BODY_BYTES` refused. Each
  error is one sentence and writes nothing; a lost batch is "the rule was
  not saved: <error>". No tool lists, edits, confirms or deletes a rule.
  Code: `Mocks.kt`.
- **Replays** (`replay_request`, ADR-0111 E.1, ADR-0121): the tool hands
  a `desktop/domain` `AskedReplay` (exchange id, the server's session id, request id,
  method, URL, device name, app id) to the `ReplayAsks` `McpServer` was
  given, `desktop/app`'s `Replays` (SDD 12), and answers at once with
  `status: "waiting"`: nothing reaches the device before the developer
  clicks "Send again" in the window (ADR-0005). Errors, nothing asked: an
  unknown id; a connection ("it has no HTTP request to send again"), a URL
  starting with `ws` ("it cannot be sent again") or a proxy exchange
  ("only an app sends a request again"), as the window shows no button
  for them; no `ReplayAsks` ("replay is not available"). Errors the
  window answers: the session ended, the device did not accept
  `interaction`, `ReplayAsks.MAX_WAITING` (20) already waiting. The same
  exchange asked while it waits is the same replay. Code: `AskReplay.kt`.

### Lifecycle

Each call class against each state, and the test that pins it. Tests are
in `desktop/mcp` unless noted. Two
internal seams of `McpServer` serve the tests: a `health` source (lost
writes) and a hook run before each tool (a call held in flight).

| Call class ↓ / state → | Store open | Store closing or closed | Store with lost writes | Server starting | Server running | Server stopping | Port taken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Tool call | answers from the store: `McpServerTest.clientListsRunsOverHttp` | error "App Monitor is closing", never an empty success: `LifecycleTest.toolCallWhileTheStoreClosesIsAnError` | answers with `warnings`: `LifecycleTest.lostWritesAreAWarning` | connection refused until `start` returns: `LifecycleTest.startReturnsOnceBound` | as "store open" | a call in flight completes within the grace: `LifecycleTest.callInFlightCompletesDuringStop`; a new connection is refused: `LifecycleTest.noConnectionAfterStop` | no server: `LifecycleTest.portTakenFailsStartWithBindException` |
| Write (`save_issue`) | queued on the writer, answered once committed, the issue then in `observeIssues`, which the window shows: `IssuesTest.aClientSavesEditsAndListsAnIssueThatTheAppsStoreShows`; an edit in the window between two MCP writes kept: `IssuesTest.anEditInTheWindowMeanwhileIsKept` | error "App Monitor is closing", nothing written: `IssuesTest.aSaveWhileTheStoreClosesIsAnError` | the write's own batch lost: error "the issue was not saved: <error>", nothing written, a warning after: `IssuesTest.aLostWriteIsAnErrorThatSaysWhy` | as tool call | as "store open" | as tool call | as tool call |
| Protocol call (`initialize`, `tools/list`, `GET`, `DELETE`) | `initialize`, `tools/list` answered, `GET`/`DELETE` `405`: `McpServerTest.statelessEndpoint` | `tools/list` still answered (no read): `LifecycleTest.toolsListNeedsNoStore` | as "store open" | as above | as "store open" | as tool call | as tool call |
| Refused request | foreign `Origin` or `Host` `403`, missing or wrong token `401`, before the SDK: `AccessTest` (`foreignOriginIsRefused`, `foreignHostIsRefused`, `wrongPortInHostIsRefused`, `missingTokenIsRefused`, `wrongTokenIsRefused`) | same | same | — | same | — | — |
| Token change | "Regenerate" or "Clear all data": the old token gets `401`, the new one passes, no restart: `AccessTest.regeneratedTokenTakesEffectAtOnce` | — | — | — | same | — | — |
| Start | binds `127.0.0.1` only: `LifecycleTest.bindsLoopbackOnly`; port from `APPMONITOR_MCP_PORT`: `McpPortTest` | not started after a failed open: `desktop/app` `McpWiringTest.noServerWithoutStorage` | starts | — | `start` twice fails: `LifecycleTest.startTwiceFails` | — | `BindException`, the app shows a status and keeps the device server: `desktop/app` `McpWiringTest.portTakenIsAStatus`, `AppMonitorAppTest` "a taken MCP port shows a status…" (`mcp-port-taken.png`) |
| Stop | `stop` returns within `STOP_TIMEOUT`, port free again: `LifecycleTest.stopFreesThePort` | the app stops MCP before closing the store: `desktop/app` `McpWiringTest.stopsBeforeTheStoreCloses` | as open | stop during start leaves nothing bound: `LifecycleTest.stopDuringStart` | as open | second `stop` returns at once: `LifecycleTest.stopTwice` | `stop` after a failed start returns: `LifecycleTest.stopAfterFailedStart` |
| Switch | off → stopped, on → started, kept across launches: `desktop/app` `McpWiringTest.switchStartsAndStops`; kept off by "Clear all data": `McpWiringTest.clearAllRegeneratesTheTokenAndKeepsTheSwitch`; turned off while `start` reads it, stays off: `McpWiringTest.switchTurnedOffDuringStartsReadStaysOff`; turned off as `start` writes it, ends off (2,000 rounds against two threads): `McpWiringTest.switchTurnedOffAsStartWritesItEndsOff` | — | — | — | — | — | — |

### Tests per tool

| Tool | Test | Built by |
| --- | --- | --- |
| `list_runs` | `ListRunsTest`: newest first, sessions and counts, paging across `limit`, the recording flagged, arguments out of range | M6.3 |
| `search_requests` | `SearchRequestsTest`: one case per filter, combined filters, a page boundary | M6.4 |
| `get_request` | `GetRequestTest`: headers and bodies as stored, a body past the cap with `body_offset` reading the rest, a body cut by the device, a binary body as base64, a UTF-8 slice cut mid-character, frames by page and by byte budget, unknown id, a mocked exchange's rule id and name | M6.4 |
| `endpoint_stats` | `EndpointStatsTest`: a crafted run with known numbers, schemes, host case, query and fragment grouped, an exchange in flight, filter and `limit`, the read cap and `truncated`, closing store, a known p99 (`knownP99`, the run `desktop/app`'s `BenchmarksUiTest` also shows); `SearchTest.sizesAreTheStoredBodyBytesUnderAFilter` and `EndpointsTest` (nearest rank) in `desktop/storage` | M6.5, M7.4 |
| `diff_runs` | `DiffRunsTest` on two crafted runs: added, gone, changed past the threshold, unchanged under it (20 % and 10 ms each), a filter on both runs, `limit`, a run against itself, a duration known on one side only, the read cap, unknown run, missing arguments, closing store (the runs read empty, answered as closing, not unknown); `desktop/app`'s `RunDiffUiTest` shows the same two runs | M6.6, M7.5 |
| `export_har` | `ExportHarTest`: every HAR 1.2 required field, a failed and an in-flight exchange, base64 and cut bodies under each `bodies` mode, frames, filter and `max_entries`, the 32 MiB refusal, closing store, lost writes in `log._warnings` | M6.7, M6.R4 |
| Connections in every tool | `ConnectionToolsTest`, the `CONNECTION` fixtures: `search_requests` row without status, `get_request`'s `connection`, `to_curl` refused, `endpoint_stats` bytes per port, `diff_runs` added, `export_har` leaving them out | M8.4 |
| gRPC status in the tools | `GrpcStatusToolsTest`, a failed call from the adapter's and the proxy's headers: `search_requests`' `grpc_status`, `endpoint_stats` and `diff_runs` error rate | M8.7 |
| `create_mock` | `MocksTest`: a rule over HTTP stored pending, sent to no device until confirmed; a failure with any method, a base64 body; every argument error, nothing written; closing store | M9.3 |
| `replay_request`, `replay`, `replay_of` | `ReplayTest`: the asked replay handed over with its session and answered `waiting`; connection, WebSocket, proxy and unknown id refused, nothing asked; each answer of the window as its error; no `ReplayAsks`; rows of `search_requests` and `get_request` linking a replay to its original, null once it is gone; `desktop/app`'s `ReplayTest` sends it to a stand-in device only once confirmed | M9.13 |
| `list_issues`, `save_issue` | `IssuesTest`: create, edit and list over HTTP, the issue in the app's `observeIssues`; status and tag filters, paging; a window edit meanwhile kept; unknown exchanges and issue refused, nothing written; every argument error; closing store; a lost write (a second connection holding SQLite's write lock); only `save_issue`, `create_mock` and `replay_request` annotated as writing, `replay_request` destructive and open world | M7.3 |
| `to_curl` | `ToCurlTest`: the command run by `sh` and curl against a local server receives the stored method, URL, headers and body; quotes, `$` and backticks escaped; empty and repeated headers; framing headers left to curl, a `Content-Length: 0` kept without a body, once, however written; binary body and NUL; `HEAD`, with a stored body; a body cut by the device; lost writes; unknown id, closing store | M6.7, M6.R3, M6.R6 |

## Limits

- No path templating in endpoints; ids in paths split them.
- No server-to-client notifications or resumable streams (ADR-0092).
- No stdio transport; stdio-only clients go through `mcp-remote`
  (ADR-0091, DEBT-0069).
- `to_curl` writes POSIX shell, not PowerShell or `cmd`.
- `replay_request` does not tell the agent that the developer refused the
  replay or that the device sent `ReplayRefused`: no replay row shows up
  (ADR-0121). A waiting replay is lost at quit and when its session ends.
- Text search and "failed only" walk the exchanges (DEBT-0063,
  re-triggered at M6.4).
