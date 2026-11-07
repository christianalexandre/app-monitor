---
id: ADR-0213
title: On macOS the window draws its own title bar, through AWT's root pane properties
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0213 — On macOS the window draws its own title bar, through AWT's root pane properties

## Context

PLAN-0018 H8.13. The owner's second look at the window (2026-11-07): the
actions belong beside "App Monitor", in the window's title bar. ADR-0205 and
ADR-0210 drew them in a 40 dp row under the system's title bar, so the window
had two bars. The owner picked option (b) on 2026-11-07 (PR #401): macOS only,
on any JDK. The app stays off the JetBrains Runtime. Its JBR API is on the
classpath, but Zulu 21 lacks the classes behind it. This ADR narrows ADR-0205
and ADR-0210.

Evidence read:

- Zulu 21.0.12's `CPlatformWindow.java` (the JDK's `lib/src.zip`) reads three
  `JRootPane` client properties: `apple.awt.fullWindowContent`,
  `apple.awt.transparentTitleBar` and `apple.awt.windowTitleVisible`. It reads
  them when the peer is made, and again on each change.
- The same file zooms a decorated window through `NSWindow zoom` when its
  `extendedState` moves to or from `MAXIMIZED_BOTH`.
- Compose 1.12.2's `WindowDraggableArea` (`foundation-desktop`, read with
  `javap`) waits for an unconsumed press with `awaitFirstDown`. It then moves
  the window with the pointer, through `JbrMoveHandler` on the JBR and
  `StandardMoveHandler` elsewhere.
- In a real window on this Mac (`titleBarScreenshots`,
  `theUnifiedTitleBarLeavesRoomForTheButtons`), AppKit takes the three
  properties. The style mask holds `NSWindowStyleMaskFullSizeContentView`, the
  title is hidden and the title bar is transparent. The zoom button's frame is
  x 47, width 14, y 6 and height 16, in points: the buttons end at 61 pt.

## Options

1. **AWT's root pane properties, and the window's own row in the title bar's
   place.** This needs no other JDK and no native call. Only macOS has these
   properties.
2. **The JetBrains Runtime's custom title bar.** It sets the title bar's height
   and the window controls' hit areas on every OS. The owner ruled it out
   (PR #401): the build and the packages would have to move to the JBR.
3. **An undecorated window that draws its own buttons.** This works on every
   OS, but it loses the system's resize edges, snapping and the full screen
   button.

## Decision

Option 1.

- **Design system**: `WindowBar(buttonsInset, area)` and `LocalWindowBar`.
  `TitleBar` keeps `buttonsInset` free before its content and draws its row
  inside `area`. `WindowBar.Plain`, the default, keeps nothing and draws the
  row as before. `WindowBar.MacButtonsInset` is 72 dp: the buttons' 61 pt and a
  gap.
- **Window** (`MacTitleBar.kt`): `unifyTitleBar(rootPane)` sets the three
  properties on macOS only, when the window's content first composes (AWT
  applies a change to a shown window too). `macWindowBar` keeps
  the inset. Its `area` is Compose's `WindowDraggableArea`, wrapped in
  `zoomOnDoubleClick`, which toggles `MAXIMIZED_BOTH`. A button under the
  pointer takes the press first, so it neither moves nor zooms the window.
  `Main.kt` provides that bar to the window when `unifyTitleBar` returns true.
- **The row** stays as ADR-0210 orders it: the sidebar toggle, "App Monitor —
  <run>" centred, Record or Stop, Clear, the tools and "Actions ⌘K". The fold
  measures the bar after the inset. At the 1000 dp minimum, 928 dp is left,
  over the 760 dp fold.
- **Windows and Linux** keep the system's title bar and the row under it
  (DEBT-0132).

## Consequences

- On macOS the window has one bar, and the window's title text is gone from it.
  The title is still set, so the Dock, Mission Control and the Window menu
  still name the window.
- ADR-0178's appearance still applies: the system's buttons follow the picked
  theme, and the row is drawn in the theme's title bar color.
- The system's buttons sit in a 28 pt title bar, centred about 14 pt from the
  top. The row is 40 dp, centred at 20 dp, so the buttons sit about 6 dp above
  the row's centre. The owner judges that in H8.6.
- The loop cannot drive a real window (DEBT-0124). The drag, the double click
  and whether AppKit passes clicks in the top 28 pt through to Compose are for
  the owner's look in H8.6. `zoomOnDoubleClick` is tested offscreen against a
  button and an empty part.
- A double click always zooms. It does not read the user's "double-click a
  window's title bar to" choice in System Settings.
- While a dialog's `Overlay` covers the window, its scrim takes the press, so
  the bar does not move the window then.
- No CI cost: `MacTitleBarTest` runs in `:desktop:app:test` in under two
  seconds. The real window check runs only under `titleBarScreenshots`.
