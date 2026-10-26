---
id: ADR-0153
title: Field test polish of the traffic window, the panes kept across launches
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-26
superseded_by:
---

# ADR-0153 — Field test polish of the traffic window, the panes kept across launches

## Context

After ADR-0151 and ADR-0152 the owner asked, during the field test, for
six follow-ups listed in the review of those changes, and for one more
pass over the layout:

- The search fields were Material's `OutlinedTextField`, 56 dp tall, in
  the toolbar and under the detail's tabs.
- Text / JSON / Hex were three loose text buttons.
- "Copied" sat in the detail's heading, pushing the URL, and stayed.
- `CheckboxRow` put its label against the box ("Failed only").
- The panes' widths and the hidden devices pane were lost on relaunch
  (ADR-0151 kept them in `remember` state).
- The search inside a request had no jump to the next match (DEBT-0096).

The layout pass found four more, read on the screenshots of
`./gradlew :desktop:app:test`: request rows 40 dp tall where Proxyman
and Charles use 24–28 dp; "↑" and "↓" as `TextButton`s took 64 dp
each (Material's minimum width); a request row spent its URL's room on
`https://`; the detail's heading did not show the status (the reference
client shows it beside the URL, `LogDetailView.swift`).

The reference (standing owner decision): `mibo-smart-app` builds its
search and text fields on foundation's `BasicTextField`, not Material's
(`core/ui/designsystem/.../component/MiboSearchField.kt` and
`MiboTextField.kt`; no `OutlinedTextField` in its design system). The
layout is stored the way `ui.theme` is (ADR-0145).

## Options

1. **Design-system components on foundation (`SearchField`, `GlyphButton`,
   `Toast`), `ToggleChip` for the formats, the panes' layout as one
   `ui.panes` setting written when a drag ends.** Small, Material-free,
   one store write per drag.
2. **Keep Material's fields and shrink them with negative padding.**
   Fights Material's minimums and breaks its label animation.
3. **Write the panes' widths on every drag step.** Hundreds of store
   writes per drag through the queue.

## Decision

Option 1.

- `desktop/designsystem/Fields.kt`: `SearchField` (32 dp, placeholder,
  "×" that empties it), `GlyphButton` (a one-character button with a
  description), `Toast` (inverse colors). `CodeText(current)` draws the
  current match on `highlightCurrent`; `lineHeightOf(role)` lets a list
  scroll to a line. `CheckboxRow` spaces its label; `PaneDivider` takes
  `onDragStopped`.
- `desktop/domain`: `PaneLayout` (shown, devices dp, requests dp; 240 and
  480 by default) and `PaneLayoutRepository`; `desktop/data`:
  `paneLayoutRepository()` over `ui.panes` = `shown|hidden,<dp>,<dp>`,
  unreadable = defaults; written back after "Clear all data", as the
  theme is. `TrafficViewModel.panes` and `setPanes`; without a repository
  the layout stays in memory.
- `desktop/feature/traffic`: both search fields are `SearchField`s; the
  copy note is a `Toast` at the detail's bottom that goes after
  `NOTE_SHOWN` (2 s); the formats are `ToggleChip`s; on the Body tab "N of
  M", "↑" and "↓" walk `bodyMatches` and scroll to the chunk's row plus
  the match's line minus three; the heading starts with the status
  badge (`statusTone`, shared with the rows); a request row shows its URL
  without `https://` (a plain `http://` stays: it says no TLS), its
  semantics keep the whole URL; a request row's vertical padding is
  `Spacing.xs`, not `md` (about 32 dp a row, not 40: a fifth more rows).
- This replaces ADR-0151's "widths kept for the window's life only".

## Consequences

- The toolbar and the detail gain about 24 dp of height each.
- Pays DEBT-0096.
- The scroll to a match assumes one line per text line (bodies do not
  wrap) and the Body tab's row layout (`bodyRowOf`); a change to either
  moves the jump, and `DetailTabsUiTest` only checks "N of M".
