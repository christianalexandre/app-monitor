---
id: PLAN-0008
title: Issues, benchmarks, export
status: Done # Draft | Active | Done | Abandoned
milestone: M7
started: 2026-10-25
finished: 2026-10-25
---

# PLAN-0008 — Issues, benchmarks, export (M7)

## Goal

The desktop app lets the developer save issues (exchanges, a note, tags, a
status) in the window and an agent save and list them over MCP
(`save_issue`, `list_issues`), shows per-endpoint benchmarks (count, error
rate, p50/p95/p99, bytes) and a run-to-run diff for a run or the filter,
and exports HAR and cURL from the window, with the same code the MCP tools
`endpoint_stats`, `diff_runs`, `export_har` and `to_curl` use.

## Tasks

M6's lessons applied: a design that adds state a background task also
reads (issues against the retention pass and "Clear all data"; an MCP
write against the window's edits) names its writers and their order, with
the test that pins each; export formats run against their real consumer
(curl, the HAR 1.2 check) from the first task that ships them. M5's kept:
the lifecycle table lists every call class against each store state.

- [x] **M7.1 Issue decisions.** ADR-0095 `Accepted`. One stop: what an
  issue holds, whether its exchanges outlive retention, what `save_issue`
  may write without a confirmation in the window, and what deleting and
  "Clear all data" do to issues.
  Accept: ADR-0095 merged by the owner with `status: Accepted`.
- [x] **M7.2 Issues in the store and the window.** `desktop/storage`
  migration and queries for issues per ADR-0095, the retention pass and
  "Clear all data" honouring them; in `desktop/app`, "Save as issue" on
  the selected exchanges, an issue list with status and tag filters, and
  the issue's note, tags and status editable. The design (SDD 09 and SDD
  01) names every writer of an issue and of its linked exchanges and
  their order.
  Accept: `./gradlew :desktop:storage:test :desktop:storage:verifySqlDelightMigration :desktop:app:test`
  — a linked exchange kept past both retention bounds per ADR-0095, an
  issue survives a reopen, an offscreen screenshot `issues.png`.
- [x] **M7.3 `save_issue` and `list_issues`.** The two MCP tools per
  ADR-0095, SDD 10's schemas and lifecycle table extended with the first
  write (store closing, lost write, an edit in the window at the same
  time).
  Accept: `./gradlew :desktop:mcp:test` — an MCP client over HTTP saves,
  edits and lists an issue, unknown exchange ids refused, a save while
  the store closes is an error; the issue then shown by `desktop/app`'s
  store.
- [x] **M7.4 Benchmarks in the window.** `endpoint_stats`' grouping moved
  where both `desktop/mcp` and `desktop/app` use it, p99 added to both; a
  benchmarks view of the selected run or the filter: per endpoint count,
  status classes, error rate, p50/p95/p99, bytes, sortable.
  Accept: `./gradlew :desktop:mcp:test :desktop:app:test` — the crafted
  run of `EndpointStatsTest` with a known p99, the same numbers in the
  window's model, an offscreen screenshot `benchmarks.png`.
- [x] **M7.5 Run diff in the window.** Two runs picked in the window and
  compared per endpoint with `diff_runs`' code: added, gone, changed
  (count, error rate, p50, p95), unchanged as a count.
  Accept: `./gradlew :desktop:app:test` — two crafted runs with known
  differences shown as `DiffRunsTest` computes them, an offscreen
  screenshot `diff.png`.
- [x] **M7.6 HAR and cURL export in the window.** "Export HAR" for the
  selected run or the filter to a file the developer picks, "Copy as
  cURL" on one exchange, both with `export_har`'s and `to_curl`'s code.
  Accept: `./gradlew :desktop:app:test` — the exported file passes the
  HAR 1.2 check of `ExportHarTest`, the copied command run by `sh` and
  curl against a local server receives the stored request.

### Milestone review

Findings of the `code-review` of `44516a4..35f6ac8`. One is a correctness
finding normal use hits and became a task. Eight went to DEBT-0074 (a
committed `save_issue` answered as closing, the window's exports without
a closing check, a double-clicked Save, an uncaught export exception, the
`bodies: none` hint in the window, the unknown-exchange check before the
unknown-issue one, the issue list's second query, `endpoint_stats`' own
closing check). One was rejected: the run diff's row key is `Endpoint`'s
data class `toString`, which keeps a null host apart from an empty one,
so two endpoints never share a key.

- [x] **M7.R1 A window edit splits an MCP tag that holds a comma.** (`fix/issue-editor-comma-tags`; the tags count as changed only when their field text differs from the one opened)
  The editor shows the tags joined by `, ` and compares the field split on
  `,` with the stored list, so an issue whose tag `timeout, retry` came
  from `save_issue` counts its tags as changed: a status-only edit stores
  `retry` and `timeout`.
  Accept: `./gradlew :desktop:app:test` — an MCP issue tagged
  `timeout, retry` resolved in the window keeps its one tag.

## Outcome

- **Shipped**: plan and ADR-0095 (#218); M7.2 issues in the store and the
  window (#219); M7.3 `save_issue` and `list_issues` (#220); M7.4
  benchmarks in the window, grouping moved to `desktop/storage`, p99
  added (#221); M7.5 run diff in the window (#222); M7.6 HAR and cURL
  export in the window (#223). One review round; its fix M7.R1 and the
  close in this PR.
- **Decisions**: ADR-0095 (issue decisions, the one stop), ADR-0096
  (issue rows and their writers), ADR-0097 (endpoint grouping in
  storage), ADR-0098 (window exports through `desktop/mcp`).
- **Debt**: DEBT-0074 created with the review's eight non-correctness
  findings. DEBT-0064, DEBT-0067, DEBT-0068, DEBT-0071, DEBT-0072 and
  DEBT-0073 fired as their files were touched and were partly paid or
  re-triggered (DEBT-0073's three `to_curl` parts paid by M7.6). No
  correctness finding is left open.
- **Deviations**: none in the task list; the review added M7.R1 and
  rejected one finding (the run diff's row key).
- **Lessons**: naming every writer of an issue and their order in SDD 09
  (M6's lesson) held: the review found no lost or reordered issue write
  in normal use, only shutdown and slow-write corners. Running the export
  formats against curl and the HAR check from M7.6 itself left no replay
  finding. What slipped was a round trip through a text field: a list
  shown joined and parsed back is not the list stored when an item can
  hold the separator; the next plan with such a field should test a
  value written by the other writer.
