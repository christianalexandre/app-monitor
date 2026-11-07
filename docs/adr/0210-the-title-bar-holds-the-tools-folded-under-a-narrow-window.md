---
id: ADR-0210
title: The title bar holds the tools as icon buttons with tooltips, folded into "⋯" under a narrow window
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0210 — The title bar holds the tools as icon buttons with tooltips, folded into "⋯" under a narrow window

## Context

PLAN-0018 H8.10. The owner's first look at the window (2026-11-07): besides the
palette, the title bar should hold the actions and the tools. ADR-0205 put
Record or Stop, Clear and "Actions" there, and ADR-0206 turned "Actions" into
the ⌘K palette. Every other screen was one palette entry away. This ADR
narrows ADR-0205's title bar. The palette, its shortcuts and the sidebar's gear
stay.

Evidence read:

- `TrafficCommands` (`TrafficPalette.kt`) already holds what the palette's
  entries and the shortcuts ⌘S, ⌘E and ⌘, run. "Save" shows only on the live
  list with a row ticked or picked. "Compare runs" shows only with two
  recordings or more.
- PR #396 (ADR-0209) added `AppIcons` and `IconButton`. They have icons for
  Save, Export, Issue, Chart, Mask, Pause and Gear, but none for comparing.
- Compose for Desktop 1.12.2's `foundation-desktop` has
  `androidx.compose.foundation.TooltipArea(tooltip, modifier, delayMillis,
  tooltipPlacement, content)`, read with `javap` from the Gradle cache. It is
  `@ExperimentalFoundationApi`.
- The window's minimum width is 1000 (`Main.kt`, `MIN_WIDTH`).
  `TitleBarUiTest` renders the window at 1000 dp: with every tool shown, the
  title still has about 450 dp (`title-bar-narrow.png`). The tools take about
  240 dp.

## Options

1. **Icon buttons with a tooltip, built from `TrafficCommands`, folded below a
   width.** The tool list is built once from what the palette runs, so the bar
   and the palette cannot drift apart. A tool the palette hides is dimmed
   instead of removed, so the bar does not move when a row is picked.
2. **Text buttons.** These are easier to read, but eight labels take more than
   500 dp and push the title out at the minimum width.
3. **A custom tooltip popup.** This needs no experimental API, but rebuilds what
   `TooltipArea` already does: hover delay, placement and dismissal.

## Decision

Option 1.

- **Design system**: `Tooltip(text) { … }` wraps `TooltipArea`. It shows a
  `TooltipBubble` 500 ms after the pointer rests, 16 dp under the pointer. The
  bubble draws the surface color on the text color, in the caption role.
  `IconButton` gains `tooltip` (its description shown in a `Tooltip`) and
  `enabled` (dimmed to 0.4 and inert). `AppIcons.Compare` (two opposite arrows)
  joins the set. The bubble, a tooltip button and a dimmed button are in the
  gallery.
- **Tools**: `TrafficCommands.tools()` returns, in order:
  - Save ⌘S, on the live list only, dimmed with nothing ticked or picked;
  - Export HAR ⌘E;
  - Issues;
  - Benchmarks;
  - Compare runs, dimmed under two recordings;
  - Mocks;
  - Breakpoints;
  - Settings ⌘,.

  Each tool's `onClick` is the call its palette entry makes, and its tag is the
  entry's. A tool is enabled exactly when the palette lists its entry.
- **Fold**: under 760 dp of title bar, the tools leave the bar for a "⋯"
  `MenuButton`, which lists only the enabled ones. The window's 1000 dp minimum
  keeps them in the bar on every desktop that honors it. The fold covers window
  managers that do not honor the minimum, and any later change to it.

## Consequences

- Every screen is one click away again. The palette, the shortcuts and the
  sidebar's gear stay as other ways in.
- A tool's tooltip can only be seen on hover, so no screenshot shows it. The
  gallery draws the `TooltipBubble` itself.
- `TooltipArea` is experimental. A Compose update that changes it breaks only
  `Tooltip.kt`.
- `TrafficViewModelTest` checks that each tool and its palette entry run the
  same thing and show together. `TitleBarUiTest` opens Settings, Issues and
  Breakpoints from the bar at 1000 dp, and Settings from the "⋯" at 740 dp.
