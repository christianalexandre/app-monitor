---
id: ADR-0151
title: Panes resize by dragging their rule, and the devices pane hides
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-26
superseded_by:
---

# ADR-0151 — Panes resize by dragging their rule, and the devices pane hides

## Context

ADR-0146 laid out the window as a toolbar over three panes: devices fixed
at 240 dp, then the requests and the detail sharing the rest 1.5 to 1.
During the field test the owner found every pane fixed and the request
list too wide, and asked to collapse the devices pane and drag the others.

The references (standing owner decision, `AGENTS.md`):

- `nowinandroid`'s `app/.../ui/NiaApp.kt` (at `2982e63`) shows list and
  detail through `rememberListDetailSceneStrategy` (Material 3 adaptive,
  Navigation 3), with no drag handle; it targets phones and tablets.
- `mibo-smart-app` has no split pane; its
  `core/ui/designsystem/.../component/MiboSwipeToDelete.kt` moves content
  with foundation's `draggable` over a `rememberDraggableState`.

Neither has a resizable desktop pane, so the design system gets its own,
built the way mibo drags.

## Options

1. **A `PaneDivider` in the design system: the 1 px rule inside a 6 dp
   band with the resize cursor, `draggable` sideways, reporting dp.** The
   screen keeps each width in state and clamps it. Material-free, small.
2. **Material 3 adaptive's `ListDetailPaneScaffold` with
   `VerticalDragHandle`.** Two panes, not three; would bring Material 3
   into a Material 2 design system (ADR-0134).
3. **Swing's `JSplitPane` around Compose panels.** Mixes toolkits and
   their themes.

## Decision

Option 1.

- `desktop/designsystem`: `PaneDivider(onDrag: (Dp) -> Unit)`, in the
  gallery.
- `desktop/feature/traffic`: the devices pane opens at 240 dp, drags to
  240–400 dp (its links do not fit narrower) and hides from the toolbar's
  "◂ Devices" / "▸ Devices"; the request list opens at 480 dp, drags from
  360 dp up to what leaves the detail 360 dp; the detail takes the rest.
  Under 560 dp a request row leaves out its start time so the URL keeps
  room.
- Widths and the hidden pane live in the screen's `remember` state: a
  relaunch starts from the defaults.
- The window keeps at least 1000 × 600 px, so the three minimums and the
  two 6 dp rules (`PaneDividerWidth`) always fit.

## Consequences

- The request list no longer grows with the window; the detail does.
- `PanesUiTest` drags both rules with the mouse, checks the minimum and
  the hidden pane; `panes.png` and `devices-hidden.png` show them.
- Keeping the widths across launches would be a setting like `ui.theme`;
  not done until asked.
