# 10 — Desktop MCP server

> **Partly built** (ADR-0066). Built: the server, access checks, token,
> lifecycle, `list_runs` (M6.3), `search_requests` and `get_request` (M6.4),
> `endpoint_stats` (M6.5), `diff_runs` (M6.6), `export_har` and `to_curl`
> (M6.7). Not built yet: the app wiring and switch (M6.8); it removes its
> mark.

`desktop/mcp` serves the stored traffic to AI agents over MCP, read-only,
from inside the desktop process (ADR-0005). Decisions: ADR-0089 (what the
tools return), ADR-0090 (who can call, port, on by default), ADR-0091
(clients), ADR-0092 (SDK, transport, modules). Code: `desktop/mcp/`, used
by `desktop/app`; it reads `desktop/storage` (SDD 09) and never writes to
it, except the two settings below.

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
                                    Storage reads (Dispatchers.IO, one transaction each)
```

- `McpServer(storage, access, port)` builds one SDK `Server` with every
  tool, and an `embeddedServer(CIO, host = "127.0.0.1", port)`;
  `start()` binds and returns the port, `stop()` stops it. The port is
  `47475`, or `APPMONITOR_MCP_PORT` when set to a number (ADR-0090 D.1);
  tests pass `0`. A taken port is a `java.net.BindException` (CIO reports it
  as a cancellation caused by one; `start` rethrows the cause). `start`
  and `stop` hold one lock: a `stop` during `start` waits for it, then
  frees the port; the server is single use.
- Stateless (ADR-0092): no `Mcp-Session-Id`, `GET` and `DELETE` answer
  `405`, each `POST` is one JSON response. Server capabilities: `tools`
  only, `listChanged = false`.
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

> The switch, `mcp.enabled` and the settings rows are **not built yet**
> (M6.8).

`McpAccess` keeps two keys in the store's `settings` table (SDD 09):
`mcp.token` and `mcp.enabled` (`true` or `false`, missing = `true`,
ADR-0090 C.1).

- At launch, after `Storage.open`: no token → 32 bytes from `SecureRandom`,
  base64url without padding, written through the queue (`setSetting`).
- `token` is a `StateFlow` the plugin reads on each request, so
  "Regenerate" and "Clear all data" (which deletes the key, ADR-0088) take
  effect without a restart: the app calls `access.regenerate()` after
  `clearAll()`.
- `McpAccess.open(storage)` reads or makes the token, `regenerate()`
  replaces it in memory at once and in the store through the queue.
- The settings show the state, the address, and the command to paste:
  `claude mcp add --transport http app-monitor http://127.0.0.1:<port>/mcp --header "Authorization: Bearer <token>"`.
- The switch writes `mcp.enabled` and starts or stops the server.

### Order in the app

> **Not built yet** (M6.8).

1. `Storage.open`, approvals, `McpAccess.open(storage)`.
2. Device server and, when enabled, MCP server started side by side; a
   failed MCP start (`BindException`: port taken) is a status in the
   settings, "Port 47475 in use", and nothing else stops.
3. On exit: MCP server stopped (grace 500 ms, then `STOP_TIMEOUT`), device
   server stopped, then `Storage.close`. Same `once` and shutdown hook as
   the device server.
4. Storage failed at open → neither server starts (SDD 09).

### Results, common to every tool

- One `TextContent` holding a JSON object (`to_curl`: the command); no `outputSchema`. Field names
  in `snake_case`. Times are ISO 8601 UTC strings (`2026-10-24T13:02:11.120Z`),
  durations `duration_ms`, sizes in bytes. Ids are the store's row ids.
- An error is a `CallToolResult` with `isError = true` and one sentence:
  an argument out of range, an unknown id, or the store closing. An unknown
  tool or malformed JSON-RPC is the SDK's JSON-RPC error.
- **Store closing.** `Storage`'s reads return an empty list or null once it
  closes (SDD 09), which would read as "nothing stored". Each tool checks
  `storage.isClosing` (a new read-only property) **after** its reads and
  answers the error "App Monitor is closing" instead.
- **Lost writes.** When `storage.health` counts unsaved events, the result
  carries `"warnings": ["N events were not saved: <error>"]` (`to_curl`: a
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
`run_id`, `session_id`. Unset fields match all. Code: `Filters.kt`, which
also writes the exchange row both `search_requests` and `get_request`
return (`capture_layer` as `platform`, `adapter`, `connection`, `proxy`,
`unspecified`; `duration_ms` null unless both times are known).

| Tool | Input | Output | Store |
| --- | --- | --- | --- |
| `list_runs` | `limit` (1–100, 20), `before_id` | `runs[]`: `id`, `name`, `started_at`, `ended_at`, `current`, `exchange_count`, `sessions[]` (`session_id`, `device_id`, `device_name`, `platform` (`android`, `ios`, `jvm`, `unspecified`), `os_version`, `app_id`, `app_version`, `sdk_name`, `connected_at`, `disconnected_at`); `next_before_id`, null on a page short of `limit`. Newest first. | `runSummaries(beforeId, limit)`: runs, their sessions and exchange counts in one read |
| `search_requests` | filter fields, `after_id`, `limit` (1–200, 50) | `requests[]`: `id`, `session_id`, `method`, `url`, `status_code`, `failed`, `error`, `started_at`, `duration_ms`, `capture_layer`, `frame_count`; `next_after_id`. Oldest first. No headers, no bodies (ADR-0089 B.1). | `searchExchanges(filter, afterId, limit)` |
| `get_request` | `id` (required), `part` (`request`, `response`, `both`; `both`), `body_offset` (0), `body_max_bytes` (1–262 144, 16 384), `frames_after_id`, `frames_limit` (0–200, 20) | the row of `search_requests`, `request` (`method`, `url`, `headers[]`, `body`), `response` (`status_code`, `headers[]`, `body`), `frames` (`items[]`: `id`, `at`, `direction`, `opcode`, `payload` as a body; `next_after_id`). `part` leaves the other side `null`; `body_offset` applies to both bodies, frame payloads start at 0. Frames: `direction` `sent`/`received`, `opcode` `text`, `binary`, `ping`, `pong`, `close`. The frames page also stops once its payloads reach `body_max_bytes`, after at least one frame; `next_after_id` is set when it stopped early or is full to `frames_limit`. Unknown id: "no exchange with id N". | `exchange(id)`, `frames(id, afterId, limit)` |
| `endpoint_stats` | filter fields, `limit` (1–500, 50) | `endpoints[]` by count, descending, then by key: `method`, `host`, `path`, `count`, `status` (`1xx`…`5xx`, `failed`: counts), `error_rate`, `duration_ms` (`min`, `p50`, `p95`, `max`; null when no exchange has both times), `request_bytes`, `response_bytes` (totals); `endpoint_count` (before `limit`), `exchanges_read`, `truncated` | `searchExchangeSizes(filter, afterId, limit)`: the rows with their stored body sizes, 0 when none |
| `diff_runs` | `base_run_id`, `run_id` (required), filter fields, `limit` (1–500, 50, per list) | `base_run_id`, `run_id`; `added[]` (in `run_id` only) and `gone[]` (in `base_run_id` only), as `endpoint_stats`' endpoints; `changed[]` (`method`, `host`, `path`, and `count`, `error_rate`, `p50_ms`, `p95_ms`, each `base`, `run`, `delta`); `added_count`, `gone_count`, `changed_count` (before `limit`); `unchanged` (a count); `truncated` (either run hit the read cap). Unknown run: "no run with id N". | `runs()`, then `endpoint_stats`' grouping once per run |
| `export_har` | filter fields, `max_entries` (1–1 000, 100), `bodies` (`none`, `capped`, `full`; `capped`) | HAR 1.2 JSON as the text; `capped` bodies at 16 KiB each | `searchExchanges`, `exchange(id)`, `frames` |
| `to_curl` | `id` (required) | a POSIX shell command as the text | `exchange(id)` |

Details the table leaves out:

- **Endpoint** (`endpoint_stats`, `diff_runs`): method, the `host` column
  lower-cased, and the URL's path without query or fragment (`/` when
  empty); the scheme is dropped, so `ws`/`wss` and `http`/`https` group
  together (pays DEBT-0058's grouping half). No path templating:
  `/users/1` and `/users/2` are two endpoints. `error_rate` = (status ≥ 400
  or failed) / count; an exchange still in flight counts in `count` only.
  Durations over exchanges with both times, percentiles by nearest rank
  (the smallest value with at least p % at or below it); bytes are stored
  body bytes, after the device's cut. The tool reads at most 100 000
  exchanges, oldest first, by page of `PAGE_SIZE`, and groups them in
  Kotlin (`EndpointStats.group`, which `diff_runs` reuses); `truncated` is
  true when a matching exchange is left past that. Code: `EndpointStats.kt`.
- **Changed** (`diff_runs`): an endpoint in both runs whose count or
  error rate differs, or whose p50 or p95 moved by at least 20 % of the
  base and 10 ms, or is known in one run only (`delta` null); the others
  are counted in `unchanged`. `changed` is sorted by the error rate's
  change, then p95's, in absolute value, largest first, then by key. The
  filter fields apply to both runs; `run_id` is the compared run. Code:
  `DiffRuns.kt`.
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
  matches than `max_entries` → `log.comment` says so. Output past 32 MiB of
  text (counted entry by entry) is refused with "narrow the filter or use
  bodies: none". `full` is for a developer who saves the file: Claude Code
  writes a result over 50 000 characters to a file (ADR-0089). Code:
  `ExportHar.kt`.
- **cURL** (`to_curl`): `curl --globoff -X '<method>' '<url>'` (`--head`
  for `HEAD`, which `-X` would leave waiting for a body), one
  `-H '<name>: <value>'` per stored header in order, the values as stored
  (masked ones stay masked), an empty value as `-H '<name>;'` (curl drops
  `<name>:`); text bodies as `--data-raw '<body>'`, binary ones, and text
  holding NUL (no shell argument can), as
  `printf %s '<base64>' | base64 -d | curl … --data-binary @-`. Single
  quotes escaped as `'\''`; one argument per line, joined by ` \`. A body
  cut by the device adds a first line
  `# body truncated by the device: <stored> of <original> bytes`. No
  request event stored: "exchange N has no request stored". Code:
  `ToCurl.kt`.

### Lifecycle

Each call class against each state, and the test that pins it. Tests are
in `desktop/mcp` unless noted; the `desktop/app` ones come with M6.8. Two
internal seams of `McpServer` serve the tests: a `health` source (lost
writes) and a hook run before each tool (a call held in flight).

| Call class ↓ / state → | Store open | Store closing or closed | Store with lost writes | Server starting | Server running | Server stopping | Port taken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Tool call | answers from the store: `McpServerTest.clientListsRunsOverHttp` | error "App Monitor is closing", never an empty success: `LifecycleTest.toolCallWhileTheStoreClosesIsAnError` | answers with `warnings`: `LifecycleTest.lostWritesAreAWarning` | connection refused until `start` returns: `LifecycleTest.startReturnsOnceBound` | as "store open" | a call in flight completes within the grace: `LifecycleTest.callInFlightCompletesDuringStop`; a new connection is refused: `LifecycleTest.noConnectionAfterStop` | no server: `LifecycleTest.portTakenFailsStartWithBindException` |
| Protocol call (`initialize`, `tools/list`, `GET`, `DELETE`) | `initialize`, `tools/list` answered, `GET`/`DELETE` `405`: `McpServerTest.statelessEndpoint` | `tools/list` still answered (no read): `LifecycleTest.toolsListNeedsNoStore` | as "store open" | as above | as "store open" | as tool call | as tool call |
| Refused request | foreign `Origin` or `Host` `403`, missing or wrong token `401`, before the SDK: `AccessTest` (`foreignOriginIsRefused`, `foreignHostIsRefused`, `wrongPortInHostIsRefused`, `missingTokenIsRefused`, `wrongTokenIsRefused`) | same | same | — | same | — | — |
| Token change | "Regenerate" or "Clear all data": the old token gets `401`, the new one passes, no restart: `AccessTest.regeneratedTokenTakesEffectAtOnce` | — | — | — | same | — | — |
| Start | binds `127.0.0.1` only: `LifecycleTest.bindsLoopbackOnly`; port from `APPMONITOR_MCP_PORT`: `McpPortTest` | not started after a failed open: `desktop/app` `McpWiringTest.noServerWithoutStorage` | starts | — | `start` twice fails: `LifecycleTest.startTwiceFails` | — | `BindException`, the app shows a status and keeps the device server: `desktop/app` `McpWiringTest.portTakenIsAStatus` |
| Stop | `stop` returns within `STOP_TIMEOUT`, port free again: `LifecycleTest.stopFreesThePort` | the app stops MCP before closing the store: `desktop/app` `McpWiringTest.stopsBeforeTheStoreCloses` | as open | stop during start leaves nothing bound: `LifecycleTest.stopDuringStart` | as open | second `stop` returns at once: `LifecycleTest.stopTwice` | `stop` after a failed start returns: `LifecycleTest.stopAfterFailedStart` |
| Switch | off → stopped, on → started, kept across launches: `desktop/app` `McpWiringTest.switchStartsAndStops` | — | — | — | — | — | — |

### Tests per tool

| Tool | Test | Built by |
| --- | --- | --- |
| `list_runs` | `ListRunsTest`: newest first, sessions and counts, paging across `limit`, current run flagged, arguments out of range | M6.3 |
| `search_requests` | `SearchRequestsTest`: one case per filter, combined filters, a page boundary | M6.4 |
| `get_request` | `GetRequestTest`: headers and bodies as stored, a body past the cap with `body_offset` reading the rest, a body cut by the device, a binary body as base64, a UTF-8 slice cut mid-character, frames by page and by byte budget, unknown id | M6.4 |
| `endpoint_stats` | `EndpointStatsTest`: a crafted run with known numbers, schemes, host case, query and fragment grouped, an exchange in flight, filter and `limit`, the read cap and `truncated`, closing store, nearest rank; `SearchTest.sizesAreTheStoredBodyBytesUnderAFilter` in `desktop/storage` | M6.5 |
| `diff_runs` | `DiffRunsTest` on two crafted runs: added, gone, changed past the threshold, unchanged under it (20 % and 10 ms each), a filter on both runs, `limit`, a run against itself, a duration known on one side only, the read cap, unknown run, missing arguments, closing store | M6.6 |
| `export_har` | `ExportHarTest`: every HAR 1.2 required field, a failed and an in-flight exchange, base64 and cut bodies under each `bodies` mode, frames, filter and `max_entries`, the 32 MiB refusal, closing store | M6.7 |
| `to_curl` | `ToCurlTest`: the command run by `sh` and curl against a local server receives the stored method, URL, headers and body; quotes, `$` and backticks escaped; empty and repeated headers; binary body and NUL; `HEAD`; a body cut by the device; lost writes; unknown id, closing store | M6.7 |

## Limits

- No path templating in endpoints; ids in paths split them.
- No server-to-client notifications or resumable streams (ADR-0092).
- No stdio transport; stdio-only clients go through `mcp-remote`
  (ADR-0091, DEBT-0069).
- `to_curl` writes POSIX shell, not PowerShell or `cmd`.
- Text search and "failed only" walk the exchanges (DEBT-0063,
  re-triggered at M6.4).
