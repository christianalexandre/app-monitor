---
id: PLAN-0012
title: Desktop clean architecture and design system
status: Active # Draft | Active | Done | Abandoned
milestone: H2
started: 2026-10-25
finished:
---

# PLAN-0012 — Desktop clean architecture and design system (H2)

## Goal

The desktop app follows ADR-0133: a `desktop/designsystem` module owns
every layout token and reusable component, screens live in feature
modules with view models, the data is reached only through `domain`
repositories implemented in `desktop/data`, and MCP uses the same
repositories. Nothing a user or an agent sees changes behavior. Asked by
the owner before the field test (roadmap Hold).

## Tasks

A refactor, not a feature: every task keeps the UI, MCP and storage tests
green, and moves the tests with the code they cover. A task too large for
one PR is split at once (M8's rule). Guideline
`docs/guidelines/05_desktop_architecture.md` and SDD 03 change in the
task that lands each module.

- [x] **H2.1 Design system.** (#262) `desktop/designsystem` with
  `AppMonitorTheme`, the tokens and the components the current screens
  repeat; `desktop/app` switched to the theme and the components; the
  design system rule script in `make check`; a gallery screenshot test.
  Accept: `./gradlew :desktop:designsystem:test :desktop:app:test` and
  `make check` — the rule script finds nothing outside the design system.
  Deviation: the app is on Material 2, not Material3 as ADR-0133 says;
  the design system wraps it (ADR-0134). The run diff dialog took the
  benchmarks' width (1100 → 1180 dp).
- [x] **H2.F1 iOS host tests isolated from each other's mock rules.**
  (#264)
  Added by H2.1: on `dev` at `ad15632`, `make swift` fails on macOS in
  `ConnectionCaptureTests.urlSessionTrafficIsLeftToItsOwnCapture` (status
  201, `MockTests`' mocked answer, instead of 200), twice in a row; the
  suite alone passes. The two suites run in parallel and the mock rules
  are process-wide. Accept: `make swift` green twice in a row.
  Deviation: the three suites that swap a process-wide sink nest in one
  serialized `ProcessWideHooks`. Once fixed, a second flake showed (1 run
  in 8): `MonitorClientTests.stopReleasesEverything(.backingOff)` dials a
  freed port; macOS drops the SYN when another socket holds it bound (the likely cause),
  so the 30 s connect timeout outlived the wait; that case now times out
  at 0.5 s. Fixed in the same PR.
- [x] **H2.2a Domain and data for traffic.** (#263) `desktop/domain` (runs,
  sessions, exchanges, the detail model, repository interfaces) and
  `desktop/data` (repositories over `desktop/storage`, mappers); the
  traffic panes of `ui/App.kt` read runs, sessions, requests and one
  request through them. Split from H2.2 (too large for one PR).
  Accept: `./gradlew :desktop:data:test :desktop:app:test` — the traffic
  panes call no `Storage` function but curl's.
  Deviation: done before H2.F1, which H2.1 added above it while this was
  under way; H2.F1 is next.
- [x] **H2.2b The traffic feature.** Koin and view models;
  `desktop/feature/traffic` (devices, requests, detail, runs, replay)
  moved out of `ui/App.kt`; `desktop/app` keeps the window and the
  wiring.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test` —
  the traffic tests pass against a view model over fake repositories and
  against the real store; no feature imports `desktop/storage`.
  Deviation: split (too large for one PR); the approval dialog and the
  agent's replay dialog went to H2.2c. `statusOf`, `titleOf`,
  `formatDuration`, `METHODS` and `utf8OrNull` are public in the traffic
  feature while `desktop/app`'s other screens use them. Paid DEBT-0088's
  "Show original" item: the view model reads an original past the
  loaded pages by id.
- [x] **H2.2c Approvals and an agent's replays.** (`refactor/desktop-approvals-asked-replays`) The device approval
  dialog and the dialog of a replay an agent asked move to
  `desktop/feature/traffic`, behind domain interfaces implemented in
  `desktop/app` (`ApprovalStore`, `Replays`).
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test` —
  `approval.png` and `replay-asked.png` unchanged; `ui/App.kt` holds no
  `PendingDevice` or `AskedReplay`.
  Deviation: `AskedReplay` moved from `desktop/mcp` to `desktop/domain`
  rather than being mirrored, so `desktop/mcp` depends on `desktop/domain`
  ahead of H2.5. The two dialogs are `PendingDecisions` over one
  `DecisionsViewModel`; `ApprovalRepository.pending` is a `Flow`, as the
  store maps the server's list. Both screenshots compared byte for byte
  against `origin/dev`'s.
- [x] **H2.3 Mocks and breakpoints.** (`refactor/desktop-mocks-breakpoints-features`)
  `desktop/feature/mocks` and
  `desktop/feature/breakpoints` on the same layers; the labels they share
  with the traffic feature (`formatDuration`, `METHODS`, `utf8OrNull`)
  move to a module both depend on, as no feature depends on another.
  Accept: their moved UI tests, `MockPushTest` and `HeldCallsTest` pass.
  Deviation: done before H2.2c, which H2.2b added above it while this was
  under way. The shared module is `desktop/ui` (ADR-0137), with
  `headersOf` too. `MocksUiTest` and `BreakpointsUiTest` stay in
  `desktop/app`, as the traffic UI tests did: they compose the window
  over a real store and a stand-in device; each feature gained a view
  model test over fake repositories. `fittingRules` moved to
  `desktop/domain`, which the push and the mock list share.
- [x] **H2.4a Benchmarks, run diff and HAR export.**
  (`refactor/desktop-insights-feature`) `desktop/feature/insights` with
  the benchmarks, the run diff and the HAR export, behind domain
  repositories. Split from H2.4 (too large for one PR).
  Accept: `./gradlew :desktop:feature:insights:test :desktop:app:test` —
  `BenchmarksUiTest`, `RunDiffUiTest` and `ExportUiTest` pass;
  `desktop/app` holds no `Benchmarks`, `RunDiff` or `exportHar`.
  Deviation: `titleOf` moved from the traffic feature to `desktop/ui`
  (ADR-0137), as the run diff shows run titles too. The HAR file is
  written through a new `HarRepository`, beside `ExportRepository`'s
  cURL. Fixed a race in `ExportUiTest` that failed #267's CI once: it
  clicked "Copy as cURL" before the detail pane had read the exchange.
  CI failed once in `sdk/core`'s `MonitorClientTest`: a stand-in desktop
  restarted on the port of the one just stopped found it still bound;
  the stand-in now retries that bind for two seconds.
- [x] **H2.4b Issues.** (`refactor/desktop-issues-feature`) The issue
  list, editor and "Save as issue"
  move to `desktop/feature/insights`, behind an `IssueRepository`.
  Accept: `IssuesUiTest` passes; `desktop/app` holds no `IssueList`.
  Deviation: `statusOf` moved from the traffic feature to `desktop/ui`,
  with `formatDateTime`, which the settings' answered devices show too.
  Paid DEBT-0074's in-flight guard and single query in
  `IssuesViewModel`.
- [x] **H2.4c Settings.** (`refactor/desktop-settings-feature`)
  `desktop/feature/settings` (retention, approvals answered, MCP, proxy).
  Accept: their moved UI tests pass; `desktop/app` holds no screen;
  `desktop/data`'s mappers are `internal` again (ADR-0135).
  Deviation: `StorageUnavailable`, the window shown when the store does
  not open, moved too, so `desktop/app` holds no screen. `McpStatus` and
  `ProxyStatus` became one `ServiceStatus` in `desktop/domain`;
  `McpController` and `ProxyController` implement `McpRepository` and
  `ProxyRepository`, `ApprovalStore` `AnsweredDeviceRepository`.
  `Exports.har` in `desktop/mcp` takes the window's domain filter and
  maps it itself, the last caller of the public `toStored()`; H2.5
  removes that copy with the rest of `desktop/mcp`'s store access.
  `settings.png`, `proxy.png` and `mcp-port-taken.png` compared against
  `origin/dev`'s: the same but for the run's start time.
  Paid DEBT-0081's `ProxyController` item: a failed proxy start leaves
  no empty session. DEBT-0072 and DEBT-0073 re-triggered to the code
  they name, not the files a rename touches.
- [x] **H2.5a MCP's read tools through the repositories.**
  (`refactor/mcp-read-tools-through-domain`) `list_runs`,
  `search_requests`, `get_request`, `endpoint_stats` and `diff_runs` read
  through `desktop/domain` repositories `desktop/app` hands to
  `McpServer`, and the closing check with them. Split from H2.5 (too
  large for one PR).
  Accept: `./gradlew :desktop:mcp:test :desktop:data:test` green, the
  tools' tests unchanged but for what they hand the server and the tools;
  those five files import nothing from `desktop/storage`.
  Deviation: a `TrafficQueryRepository` beside the window's
  repositories holds the paged reads (ADR-0138); `CapturedFrame` gained
  its id and `BenchmarkRepository` a `maxExchanges` cap. Paid DEBT-0081's
  HAR cap item (`ExportHar.kt` touched: connections no longer count
  against `max_entries`) and DEBT-0074's duplicate closing check, whose
  test now goes through the server.
- [ ] **H2.5b The rest of MCP through the repositories.** `export_har`,
  `to_curl`, the issue and mock tools, `replay_request`, the token and
  the store's health; `desktop/mcp` depends on `desktop/domain`, not
  `desktop/storage`.
  Accept: `./gradlew :desktop:mcp:test` unchanged and green; `desktop/mcp`'s
  main source set has no `desktop:storage` dependency (its tests may keep
  one to fill a real store).

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
