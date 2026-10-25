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
- [ ] **H2.F1 iOS host tests isolated from each other's mock rules.**
  Added by H2.1: on `dev` at `ad15632`, `make swift` fails on macOS in
  `ConnectionCaptureTests.urlSessionTrafficIsLeftToItsOwnCapture` (status
  201, `MockTests`' mocked answer, instead of 200), twice in a row; the
  suite alone passes. The two suites run in parallel and the mock rules
  are process-wide. Accept: `make swift` green twice in a row.
- [x] **H2.2a Domain and data for traffic.** (#263) `desktop/domain` (runs,
  sessions, exchanges, the detail model, repository interfaces) and
  `desktop/data` (repositories over `desktop/storage`, mappers); the
  traffic panes of `ui/App.kt` read runs, sessions, requests and one
  request through them. Split from H2.2 (too large for one PR).
  Accept: `./gradlew :desktop:data:test :desktop:app:test` — the traffic
  panes call no `Storage` function but curl's.
  Deviation: done before H2.F1, which H2.1 added above it while this was
  under way; H2.F1 is next.
- [ ] **H2.2b The traffic feature.** Koin and view models;
  `desktop/feature/traffic` (devices, approvals, requests, detail, runs,
  replay) moved out of `ui/App.kt`; `desktop/app` keeps the window and the
  wiring.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test` —
  the traffic tests pass against a view model over fake repositories and
  against the real store; no feature imports `desktop/storage`.
- [ ] **H2.3 Mocks and breakpoints.** `desktop/feature/mocks` and
  `desktop/feature/breakpoints` on the same layers.
  Accept: their moved UI tests, `MockPushTest` and `HeldCallsTest` pass.
- [ ] **H2.4 Insights and settings.** `desktop/feature/insights` (issues,
  benchmarks, run diff, export) and `desktop/feature/settings`
  (retention, MCP, proxy).
  Accept: their moved UI tests pass; `desktop/app` holds no screen;
  `desktop/data`'s mappers are `internal` again (ADR-0135).
- [ ] **H2.5 MCP through the repositories.** `desktop/mcp` depends on
  `desktop/domain`, not `desktop/storage`.
  Accept: `./gradlew :desktop:mcp:test` unchanged and green; `desktop/mcp`'s
  build file has no `desktop:storage` dependency.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
