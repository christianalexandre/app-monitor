---
id: ADR-0150
title: The macOS title bar takes the stored theme at launch
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-26
superseded_by:
---

# ADR-0150 — The macOS title bar takes the stored theme at launch

## Context

ADR-0145 paints the window's content from the stored theme, but on macOS
the title bar, menus and system dialogs are AppKit's. During the field
test the owner saw the app dark under a light title bar: a JVM app is
`Aqua` unless told otherwise, whatever the system's appearance.

Evidence read:

- The packaged runtime is JDK 21.0.12.1 (`release` in
  `App Monitor.app/Contents/runtime`). `strings` on the host JDK 21's
  `lib/libawt_lwawt.dylib` finds `apple.awt.application.appearance` beside
  `appearanceNamed:` and `setAppearance:`: AWT reads that system property
  when it starts and sets `NSApp`'s appearance from it. `system` leaves it
  unset, so it follows the system's, live.
- `javap -constants` on `sun.lwawt.macosx.CPlatformWindow` lists no
  per-window appearance property (only `apple.awt.transparentTitleBar`,
  `apple.awt.fullWindowContent`, `apple.awt.windowTitleVisible` …).

## Options

1. **Set `apple.awt.application.appearance` from `ui.theme` in `main`,
   before the first window.** One line; System follows the system live,
   Light and Dark are fixed per launch.
2. **A transparent title bar painted with the theme's surface.** Follows
   every change live, but the title text keeps AppKit's color, so it needs
   the title hidden and a band of our own; not checkable from a test.
3. **Leave it.** What the owner reported as a bug.

## Decision

Option 1. `macAppearanceOf(ThemeMode)` in `desktop/app/Main.kt` maps System
to `system`, Light to `NSAppearanceNameAqua`, Dark to
`NSAppearanceNameDarkAqua`; `main` sets it right after `openServices` reads
the theme. Other systems ignore the property.

## Consequences

- With System (the default) the title bar always matches.
- Light or Dark picked in Settings repaints the content at once, the title
  bar on the next launch (DEBT-0095).
- `MacAppearanceTest` covers the mapping; whether AppKit applies it is
  seen only by eye on a packaged app.
