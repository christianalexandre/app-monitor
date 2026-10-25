---
id: PLAN-0007
title: MCP server
status: Done # Draft | Active | Done | Abandoned
milestone: M6
started: 2026-10-24
finished: 2026-10-25
---

# PLAN-0007 — MCP server (M6)

## Goal

The desktop app serves a `desktop/mcp` MCP server inside its process over
Streamable HTTP on `127.0.0.1` (ADR-0005), reading the `desktop/storage`
store, with the read-only tools `list_runs`, `search_requests`,
`get_request`, `endpoint_stats`, `diff_runs`, `export_har` and `to_curl`,
and a developer can connect Claude Code to it by following the setup docs.

## Tasks

M5's lesson applied: before the code, the MCP server's design lists every
call class (tool calls, MCP sessions, start and stop) against each state of
the store (open, closing, closed, failed write) and of the server
(starting, running, stopping, port taken), with the test that pins each
cell. M4's lessons kept: decisions in the "stop and ask" areas of
`AGENTS.md` come first, in one stop; the platform primitives (the Kotlin
MCP SDK's version, licence and Ktor needs) are written down with their
evidence before the code; `make debt-check` decides how each fired DEBT is
handled (DEBT-0063 fires on M6.4, DEBT-0058 on M6.5).

- [x] **M6.1 MCP exposure decisions.** ADR-0089, ADR-0090, ADR-0091 `Accepted`. `Proposed` ADRs, one stop: (a) what
  the tools return — headers, URLs, bodies and frames as stored (masked on
  the device unless `masking = false`, ADR-0034, ADR-0081), size caps on a
  returned body, binary bodies; (b) who can call it — bound to
  `127.0.0.1` only, `Host` and `Origin` checks against DNS rebinding,
  whether a token is required, whether the server is on by default or
  switched on in the settings, its port; (c) which clients — Streamable
  HTTP only or a stdio shim too (ADR-0005), and what the setup docs cover.
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [x] **M6.2 MCP design on paper.** SDD 10, ADR-0092 (`docs/mcp-design`). SDD chapter for `desktop/mcp`: each
  tool's input and output schema, paging and limits, how it maps to
  `ExchangeFilter` and the store's reads; the lifecycle table (call class
  × state, M5's lesson) with the test that pins each cell; whether
  `make server` serves MCP too. ADR `Accepted` for the platform
  primitives: the Kotlin MCP SDK version and licence from its official
  source, the Ktor version it needs against the one `desktop/server`
  uses, the runtime modules the packaged app needs (ADR-0055).
  Accept: `make docs-check`; every lifecycle cell and every tool has a
  named test.
- [x] **M6.3 `desktop/mcp` module and `list_runs`.** (`feat/mcp-server-list-runs`; `desktop/mcp` moved to SDD 03's "Today" here, as its rule asks, not at M6.10.) The server per M6.1
  and M6.2, started and stopped with the app's store, `list_runs` as the
  first tool.
  Accept: `./gradlew :desktop:mcp:test` (in `make build`) — an MCP client
  connects over HTTP to a server on a temporary store and lists its runs;
  M6.2's lifecycle cases; a request with a foreign `Origin` refused.
- [x] **M6.4 `search_requests` and `get_request`.** (`feat/mcp-search-get-request`; DEBT-0063 re-triggered: its MCP clause dropped, the two measured triggers kept; an empty body cut by the device is an object, not `null`, SDD 10.) Search over the stored
  exchanges with M5.7's filters (text, method, status class, failed only,
  device, app, run) and paging; one exchange with its headers, bodies
  and frames per M6.1 (a). DEBT-0063 fires: paid or re-triggered.
  Accept: `./gradlew :desktop:mcp:test` with one case per filter, paging
  across a page boundary and a body past the cap.
- [x] **M6.5 `endpoint_stats`.** (`feat/mcp-endpoint-stats`; DEBT-0058 re-triggered: grouping drops the scheme, only an exact whole-URL match is left; `endpoint_count` added to the output.) Per endpoint (method, host and path
  without query, per M6.2) of a run or filter: count, status classes,
  error rate, duration and bytes. DEBT-0058 fires (grouping by URL).
  Accept: `./gradlew :desktop:mcp:test` (or `:desktop:storage:test` for
  the query) on a crafted run with known numbers.
- [x] **M6.6 `diff_runs`.** (`feat/mcp-diff-runs`; `limit` per list and `added_count`, `gone_count`, `changed_count`, `truncated` added to the output, as `endpoint_stats`' `endpoint_count`.) Two runs compared per endpoint: added, gone,
  and the changes in count, error rate and duration.
  Accept: `./gradlew :desktop:mcp:test` on two crafted runs with known
  differences.
- [x] **M6.7 `export_har` and `to_curl`.** (`feat/mcp-export-har-to-curl`; HAR's `time`/`wait` 0 with a `comment` where SDD 10 said -1, which HAR forbids there; cURL adds `--globoff`, `--head` and `Name;` for empty headers.) HAR 1.2 for a run or a filter,
  a cURL command for one exchange, returned as tool text.
  Accept: `./gradlew :desktop:mcp:test` — the HAR validated against the
  HAR 1.2 field list, the cURL command run against a local server
  reproducing the stored request.
- [x] **M6.8 Desktop app wiring.** (`feat/mcp-app-wiring`; `McpServer.start` probes the port with a plain `ServerSocket` first, since CIO also reports a taken port as an uncaught exception; "Clear all data" keeps the switch off when it was off; the packaged app checked: no new JDK module, ADR-0092.) The app starts the server per M6.1 (b),
  the settings show its state and address (and the switch, if M6.1 asks
  for one), a port already taken shows a status, not a crash.
  Accept: `./gradlew :desktop:app:test` with an offscreen screenshot of
  the settings showing the MCP address, and a test with the port taken.
- [x] **M6.9 Claude Code setup and live check.** (`docs/mcp-client-setup`; setup in the README, live check in SDD 02; the Android emulator's sample was the client, `liveScreenshots` approved it first; Claude Code run with `--mcp-config` on the README's `.mcp.json`, `claude mcp add` checked against its `--help` only.) Setup docs per M6.1 (c);
  a sample's traffic stored by `make desktop`, then a real MCP client
  (Claude Code with the documented setup, or the MCP Inspector) calling
  every tool against it.
  Accept: the setup docs merged; the client's calls and answers shown in
  the PR.
- [x] **M6.10 Docs sync.** (`docs/mcp-docs-sync`; no command added since M6.9, `:desktop:mcp:test` already listed; SDD 03's `desktop/mcp` row completed with every tool.) README status, SDD 01 and 03 (the planned
  `desktop/mcp` moves to "Today"), `AGENTS.md` commands.
  Accept: `make docs-check`; every command the milestone added is in
  `AGENTS.md`.

### First milestone review

Findings of the `code-review` of `45d382c..6172cd4`. Five are correctness
findings and became four tasks (the two `to_curl` replays share one).
Five went to DEBT-0071 (a refused settings write not reported, `dev` as
the packaged server's version, the HAR cap counted in characters, the
header serializer copied, durations sorted per read). None was rejected.

- [x] **M6.R1 A switch turned off during launch is overwritten.** (`fix/mcp-switch-launch-race`; a `setEnabled` before `start`'s read returns wins, the read injectable for the test)
  `McpController.start` reads `mcp.enabled` outside its lock and then
  writes the result to `enabled`, so a `setEnabled(false)` made while that
  read is in flight is undone: the server binds and the settings show it
  on, while the store says off, so the next launch is off.
  Accept: `./gradlew :desktop:app:test` with a test that turns the switch
  off while `start`'s read is held, and the server stays off.
- [x] **M6.R2 `diff_runs` racing close names a run that exists as unknown.** (`fix/mcp-diff-runs-closing`; the check moved after `runs()`, as SDD 10 already said, the read injectable for the test)
  `DiffRuns.call` checks `isClosing` before `storage.runs()`, which returns
  an empty list once the store closes, so the tool answers
  "no run with id N" instead of "App Monitor is closing".
  Accept: `./gradlew :desktop:mcp:test` with a case where the store closes
  between the check and the read, answered with the closing error.
- [x] **M6.R3 `to_curl` commands that curl cannot replay.** (`fix/mcp-to-curl-replay`; `Content-Length` and `Transfer-Encoding` left to curl, a `HEAD`'s stored body left out with a comment line) Every stored
  header is replayed with `-H`, so a body cut by the device (or a stored
  `Transfer-Encoding: chunked`) sends a `Content-Length` that does not
  match the body, and the server waits or refuses; a `HEAD` with a stored
  body emits `--head` with `--data-raw`, which curl refuses ("You can only
  select one HTTP request method!").
  Accept: `./gradlew :desktop:mcp:test` — `ToCurlTest` runs both commands
  against its local server and receives the request.
- [x] **M6.R4 `export_har` adds a field HAR 1.2 does not allow.** (`fix/mcp-har-warnings`; the warning moved to `log._warnings`, the HAR check now refuses an unknown `log` field without `_`)
  `jsonResult` puts a top-level `warnings` key next to `log` when events
  were not saved; HAR 1.2 only allows custom fields that start with `_`,
  so the saved file is not a valid HAR.
  Accept: `./gradlew :desktop:mcp:test` — the HAR with lost writes passes
  the HAR 1.2 field check, the warning still in it; SDD 10 updated.

### Second milestone review

Findings of the `code-review` of `6172cd4..0a36f7a`. Two are correctness
findings and became two tasks. Five went to DEBT-0072 (the closing check
per tool, test seams in production signatures, the HAR warning helper
copied, a `HEAD`'s truncation note replaced, framing headers asserted in
one case). One was rejected: a lower-case `head` is not `HEAD` (methods
are case-sensitive, RFC 9110 9.1), so curl sending it as is replays what
was stored.

- [x] **M6.R5 The launch race of M6.R1 is narrowed, not closed.** (`fix/mcp-switch-launch-write`; `start`'s check and write and `setEnabled`'s write in one `synchronized` block; the test races two threads 2,000 rounds, as no hook fits a window with no suspension point: it caught the old code in 2 of 6 runs)
  `setEnabled` writes `switched` and `enabled` outside the lock, so a
  `setEnabled(false)` that lands after `start` read `switched` and before
  it writes `enabled` is overwritten: the server is stopped and the store
  says off, but the settings switch shows on.
  Accept: `./gradlew :desktop:app:test` with a test that turns the switch
  off between `start`'s check and its write, and both the server and
  `enabled` end off.
- [x] **M6.R6 `to_curl` drops a stored `Content-Length: 0`.** (`fix/mcp-to-curl-empty-length`; kept only when the value is `0` and no body is sent, so a body cut to nothing by the device still gets none) An empty
  `POST` or `PUT` replayed with `-X` and no data sends no
  `Content-Length` (curl 8.7.1, checked against `nc`), so a server that
  requires one answers `411 Length Required` where the original passed.
  Accept: `./gradlew :desktop:mcp:test` — `ToCurlTest` replays an empty
  `POST` that stored `Content-Length: 0` and its server receives the
  header.

### Third milestone review

Findings of the `code-review` of `0a36f7a..6f9f01d`. None is a
correctness finding with a failure the app can reach, so no task was
added. Five went to DEBT-0073 (two `setEnabled` calls relying on call
order, which the UI dispatcher and two fair mutexes keep; the race test
guarding in some runs only; `to_curl` keeping only a literal `0`; the
framing filter's mixed rules; the tool description). One was fixed in
this PR: the tech-debt index still gave DEBT-0071's old trigger. Two were
rejected: `afterClearAll` cannot write back a stale switch, since
`setEnabled` sets `enabled` before it enqueues its write; and the race
test does not slow `make check` (1.2 s for its 2,000 rounds).

## Outcome

- **Shipped**: plan (#197); M6.1 exposure, access and client decisions
  (#198); M6.2 MCP design and platform primitives on paper (#199); M6.3
  `desktop/mcp` with `list_runs` (#200); M6.4 `search_requests` and
  `get_request` (#201); M6.5 `endpoint_stats` (#202); M6.6 `diff_runs`
  (#203); M6.7 `export_har` and `to_curl` (#204); M6.8 the server in the
  app and its settings (#205); M6.9 Claude Code setup and live check
  (#206); M6.10 docs sync (#207). Three review rounds: plan updates #208,
  #213 and this PR; fixes M6.R1 (#209), M6.R2 (#210), M6.R3 (#211), M6.R4
  (#212), M6.R5 (#214), M6.R6 (#215).
- **Decisions**: ADR-0089 to ADR-0092. ADR-0093 (#177, the release swap
  Gradle plugin) was merged during this plan but belongs to M10.
- **Debt**: DEBT-0069 to DEBT-0073 created; DEBT-0070 paid by M6.3.
  DEBT-0063 fired on M6.4 and was re-triggered without its MCP clause;
  DEBT-0058 fired on M6.5 and was narrowed to an exact whole-URL match;
  DEBT-0064, DEBT-0065, DEBT-0067 and DEBT-0068 had their triggers moved
  as the tools touched their files. Review findings left open are only
  non-correctness ones, in DEBT-0071, DEBT-0072 and DEBT-0073. No
  correctness finding is left open.
- **Deviations**: M6.3 moved `desktop/mcp` to SDD 03's "Today" itself,
  not at M6.10; M6.5 and M6.6 added counts and a `truncated` flag to
  their outputs; M6.7 wrote HAR's unknown timings as 0 with a comment,
  since HAR forbids -1 there; M6.8 probes the port with a plain
  `ServerSocket` before Ktor; M6.9 used the Android emulator's sample as
  the traffic source and checked `claude mcp add` against its `--help`
  only. The reviews added M6.R1–M6.R6; the switch's launch race took two
  rounds (R1 the read, R5 the write) and `to_curl`'s replay two (R3
  framing headers, R6 an empty body's length).
- **Lessons**: the lifecycle table of SDD 10 (M5's lesson) held: no
  review found a call racing the store's close outside a named cell but
  `diff_runs`' check order. What it did not list was state the UI
  writes while a call runs (the switch), which took two rounds; the next
  plan with a setting a background task also reads should name its
  writers and their order in the design. Replay formats (cURL, HAR)
  needed their tool run against the real consumer (curl, a HAR check)
  from the first task, not after review.
