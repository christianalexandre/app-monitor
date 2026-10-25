---
id: ADR-0133
title: The desktop app is split into a design system, core layers and feature modules
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0133 — The desktop app is split into a design system, core layers and feature modules

## Context

The owner asked, before the field test (roadmap Hold), for the desktop
app to follow a market-standard clean architecture (presentation,
domain, data, with repositories in front of data access) and to have a
design system module that owns everything about layout, as in the
owner's other project (`mibo-smart-app`, `core/ui/designsystem` and its
guideline 04). The field test will polish the interface; today that work
would land in one file.

The state on `dev` at `b5617d8`:

- `desktop/app` is one module of ~4 400 lines. `ui/App.kt` alone is
  1 360 lines: devices, requests, detail, filters, runs, settings, MCP,
  proxy and dialogs. `ui/Mocks.kt` and `ui/Breakpoints.kt` are over 400.
- The composables import `appmonitor.desktop.storage.Storage` and call it
  29 times; state lives in `remember` and in `store/*` controllers. There
  is no view model.
- No theme of our own: Material3 as is, 118 raw `.dp` and 2 `Color(0x…)`
  in `ui/`.
- `desktop/mcp` imports `desktop/storage` in 15 files: the UI and MCP
  read the same data through two different paths.
- `desktop/storage`, `desktop/server`, `desktop/proxy` and `desktop/mcp`
  are already modules with their own tests; ~20 offscreen UI tests and
  screenshots cover `desktop/app`.

The owner delegated the decisions of this ADR to the agent (2026-10-25).

## Options

1. **Keep one module, add packages and view models.** Cheapest; nothing
   stops a screen from importing `Storage` again.
2. **Core modules plus feature modules.** A design system module, a pure
   Kotlin domain, a data module implementing its repositories over
   `desktop/storage`, and one module per feature holding presentation
   only. Gradle's dependency graph enforces the direction.
3. **Three modules per feature** (`feature-x-domain`, `-data`,
   `-presentation`). The strictest; about fifteen modules for an app of
   this size, most holding a handful of files.

## Decision

Option 2.

- **Modules.**
  - `desktop/designsystem`: theme (`AppMonitorTheme`), tokens (color
    scheme light and dark, typography, spacing, radius, elevation),
    icons and every reusable component that knows no domain model
    (buttons, fields, chips, tables, split panes, dialogs, status badges,
    body viewer). Depends on Compose only.
  - `desktop/domain`: models, repository interfaces and the use cases
    that hold a rule (not one per repository call). Pure Kotlin and
    coroutines; no Compose, no SQLDelight, no Ktor.
  - `desktop/data`: repository implementations over `desktop/storage`,
    `desktop/server` and `desktop/proxy`; maps their types to the
    domain's.
  - `desktop/feature/<name>`: view models (`StateFlow` of an immutable
    UI state, functions for events) and screens. Features: `traffic`
    (devices, approvals, requests, detail, runs, replay), `mocks`,
    `breakpoints`, `insights` (issues, benchmarks, run diff, export),
    `settings` (retention, MCP, proxy). A feature depends on `domain` and
    `designsystem`, never on `data`, `storage` or another feature.
  - `desktop/app`: composition root — window, navigation, dependency
    wiring. The only module that sees both `data` and the features.
  - `desktop/mcp` reads and writes through `domain`'s repositories, not
    `desktop/storage`.
- **Libraries.** Koin (Apache-2.0) for injection, as the owner's other
  project; JetBrains' multiplatform `lifecycle-viewmodel-compose`
  (Apache-2.0) for view models. Both free.
- **Design system rules**, checked by a script in `make check`: outside
  `desktop/designsystem`, no `Color(0x`, no `MaterialTheme.colorScheme`
  or `MaterialTheme.typography`, no raw `.dp` for spacing or radius, no
  Material3 component where the design system has one. A screenshot
  gallery test renders every component in light and dark.
- **No behavior change.** Each step keeps the existing UI tests and MCP
  tests green; a screenshot may change only where a token replaced a raw
  value.

## Consequences

- A screen cannot reach the database: the Gradle graph rejects it, and
  the UI and MCP share one path to the data.
- The field test's interface work happens in tokens and components, not
  in each screen.
- More modules and a mapping layer between `storage` and `domain` types;
  a model change touches both.
- New guideline `docs/guidelines/05_desktop_architecture.md` and the SDD
  code map change with the modules that land.
