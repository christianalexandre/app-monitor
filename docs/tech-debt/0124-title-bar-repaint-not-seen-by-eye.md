---
id: DEBT-0124
title: The title bar following a picked theme was not seen by eye
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: The owner's next run of the desktop on macOS, or the first session that runs `titleBarScreenshots` with Screen Recording allowed
paid_date:
---

# DEBT-0124 — The title bar following a picked theme was not seen by eye

## What is missing

PLAN-0016 H6.2 asks for a screenshot of the title bar repainted by a theme
picked in Settings (ADR-0178). The loop's session has no Screen Recording:
`screencapture` answers "could not create image from display" and
`CGWindowListCreateImage` returns null, for its own window too. What ran instead:
`titleBarScreenshots` reads `effectiveAppearance` of a real window after each
pick, `NSAppearanceNameDarkAqua` then `NSAppearanceNameAqua`.

## Why it was accepted

The appearance AppKit reports is the one it draws the frame with. What is left is
the sight of it, and Settings driven by hand rather than `applyMacAppearance`
called directly: the theme flow reaches it in `Main.kt`, which no test runs.

## Trigger

The owner's next run of the desktop on macOS: pick Light, then Dark, in Settings.
Or the first session that runs `./gradlew :desktop:app:titleBarScreenshots` with
Screen Recording allowed, which writes `docs/screenshots/title-bar-*.png` and fails
when the two bars do not differ.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
