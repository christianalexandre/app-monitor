---
id: ADR-0137
title: What desktop features share lives in desktop/ui
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0137 — What desktop features share lives in desktop/ui

## Context

ADR-0133 says a desktop feature depends on `domain` and `designsystem`,
"never on `data`, `storage` or another feature". PLAN-0012's H2.3 moves the
mock and breakpoint screens out of `desktop/app` into
`desktop/feature/mocks` and `desktop/feature/breakpoints`. Both use helpers
that H2.2b left in `desktop/feature/traffic`: `METHODS` (the method
choice), `formatDuration` (a rule's delay or timeout), `utf8OrNull` (a body
shown as text or kept as bytes). The breakpoint dialog also reads the mock
editor's `headersOf` (one `Name: value` per line). H2.4's benchmarks and run
diff use `formatDuration` too.

None of these is a design system component: `designsystem` "knows no
domain model" (guideline 05) and `headersOf` builds the protocol's `Header`.
None is a domain rule either: they are how the window reads and writes a
value.

Both references split this out:

- nowinandroid has `core/ui` beside `core/designsystem`: composables and
  helpers that know the model (`core/ui/.../NewsFeed.kt` takes a list of
  `UserNewsResource`), and every feature depends on it: its feature
  convention plugin adds `implementation(project(":core:ui"))`
  (`build-logic/convention/.../AndroidFeatureImplConventionPlugin.kt`).
- mibo-smart-app has `core/ui/designsystem` and, beside it,
  `core/ui/camera` and `core/ui/map` (read in
  `~/Documents/mibo-smart-app/core/ui/`): UI shared by features;
  `feature/home/build.gradle.kts` depends on `:core:ui:map`.

## Options

1. **`desktop/ui`, the `core/ui` of both references.** One module that
   depends on `desktop/domain`, holds what more than one feature shows of
   the domain, and that every feature may depend on. One more module.
2. **Copy the helpers into each feature.** No new module; four helpers
   duplicated now, more with H2.4, and a fix applied to one copy only.
3. **Let a feature depend on another** (`mocks` on `traffic`). Breaks
   ADR-0133's rule and makes the feature graph order matter.

## Decision

Option 1, following nowinandroid's `core/ui` and mibo-smart-app's
`core/ui/*`: a `desktop/ui` module (package `appmonitor.desktop.ui`) with
`api(desktop/domain)`, holding what two features or more read or write of a
domain value. A helper used by one feature stays in that feature. It holds
no Compose code today; a composable shared by features and knowing a domain
model goes there too, with the Compose plugin added then.

## Consequences

- `desktop/feature/traffic`, `mocks` and `breakpoints` depend on
  `desktop/ui`; `desktop/app` too, for the benchmarks and run diff until
  H2.4 moves them.
- `designsystem` stays free of domain types; `domain` stays free of how the
  window formats them.
- Guideline 05 lists the module beside the design system.
