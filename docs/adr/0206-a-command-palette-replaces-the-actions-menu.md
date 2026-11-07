---
id: ADR-0206
title: A ⌘K command palette replaces the title bar's "Actions" menu
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-06
superseded_by:
---

# ADR-0206 — A ⌘K command palette replaces the title bar's "Actions" menu

## Context

PLAN-0018 H8.5 adds the palette of the mockup the owner approved on
2026-11-06 (`docs/screenshots/h8-mockup.html`, `Palette`;
`h8-mockup-palette.png`). ADR-0205 put the screens the window's bottom links
used to open in an "Actions ▾" menu on the title bar, until this palette.
The owner approved the palette as new scope on 2026-11-06 (PLAN-0018 *Goal*):
it adds a way in, not a feature.

Evidence read:

- The mockup's palette is 560 px wide, 90 px from the window's top on a
  scrim, with a search field ("Search requests, actions, screens…", `esc`),
  groups Requests, Actions, Open and Devices, a 24 px glyph chip per row, a
  label and a sub line, the shortcut on the right, and a footer `↵ run`,
  `↑↓ move`, `⌘K close`. Requests are the rows whose `method url` holds the
  text, one per method and path, five at most. ↑ and ↓ move, ↵ runs and
  closes, Esc closes, a click outside closes.
- The mockup lists ⌘R, ⌘S, ⌘I, ⌘M, ⌘↵, ⌘⇧C, ⌘E, ⌘⌫ and ⌘,. The window
  handles ⌘F and ⌘B only (ADR-0203, ADR-0205), on the window's root
  `onPreviewKeyEvent`, which sees a key before the focused field does.
  ⌘⌫ deletes to the line's start and ⌘⇧C, ⌘I, ⌘↵ are taken by text fields or
  the platform's habits: caught at the root, they would leave every field.
- The "Actions" menu also holds "Compare runs" (two recordings or more),
  which the plan's list for the palette leaves out.

## Options

1. **The palette in `desktop/designsystem` (`CommandPalette`, `PaletteGroup`,
   `PaletteItem`, `KeyHint`), filled by the traffic screen; the menu goes;
   the shortcuts it lists are the ones the window handles, ⌘R, ⌘S, ⌘E and ⌘,
   added.** No field loses a key it edits with, and every listed shortcut
   works without the palette.
2. **The same with every shortcut of the mockup.** ⌘⌫ in the URL filter
   would clear the live list instead of the field.
3. **Keep the menu beside the palette.** Two ways to the same screens on one
   bar, against the mockup.

## Decision

Option 1.

- **Opens** from the title bar's "Actions ⌘K" or ⌘K (Ctrl+K off macOS);
  ⌘K, Esc, a click outside or a run closes it, and the window's keys come
  back to its root.
- **Requests**: the shown list's loaded rows whose `method url` holds the
  text, case aside, one per method and path, five at most; "path" as the
  row's, "METHOD · host" under it. Running one picks it.
- **Actions**: Record or Stop (⌘R) and Clear on the live list; Save (⌘S) and
  "Save as issue" with a row picked or marked; Mock, Replay (when the detail
  can) and "Copy as cURL" with a request shown; Export HAR (⌘E).
- **Open**: Issues, Benchmarks, Mocks and Breakpoints (their labels with what waits), Compare runs (two
  recordings or more, kept from ADR-0205's menu), Settings (⌘,).
- **Devices**: "Show <device>", "app · OS" under it, one per session of the
  shown list; running one picks it as the sidebar does.
- Each item runs what its button or menu runs (`TrafficViewModel`,
  `TrafficLinks`); the palette adds no action of its own.
- The text typed is kept in the screen's `remember` while the palette is
  open, and emptied as it opens.
- A dialog closing took the focus with it, and no shortcut reached the
  window's root until a click (seen in `PaletteUiTest`): `TrafficLinks.covered`
  says a dialog is open, and the root takes the focus back as it closes.

## Consequences

- The title bar's last control is "Actions ⌘K"; the screens it led to are
  one key and one more away, as the mockup has them.
- ⌘I, ⌘M, ⌘↵, ⌘⇧C and ⌘⌫ are not shortcuts: their actions are in the
  palette and on the detail's bar.
- Requests come from the rows loaded, not the store: an endpoint past the
  loaded pages is found by the list's URL filter (ADR-0203), as before.
- SDD 01 and SDD 03 describe the palette.
