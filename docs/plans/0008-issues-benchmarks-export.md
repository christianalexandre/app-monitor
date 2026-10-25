---
id: PLAN-0008
title: Issues, benchmarks, export
status: Active # Draft | Active | Done | Abandoned
milestone: M7
started: 2026-10-25
finished:
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
- [ ] **M7.5 Run diff in the window.** Two runs picked in the window and
  compared per endpoint with `diff_runs`' code: added, gone, changed
  (count, error rate, p50, p95), unchanged as a count.
  Accept: `./gradlew :desktop:app:test` — two crafted runs with known
  differences shown as `DiffRunsTest` computes them, an offscreen
  screenshot `diff.png`.
- [ ] **M7.6 HAR and cURL export in the window.** "Export HAR" for the
  selected run or the filter to a file the developer picks, "Copy as
  cURL" on one exchange, both with `export_har`'s and `to_curl`'s code.
  Accept: `./gradlew :desktop:app:test` — the exported file passes the
  HAR 1.2 check of `ExportHarTest`, the copied command run by `sh` and
  curl against a local server receives the stored request.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
