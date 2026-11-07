---
id: ADR-0211
title: The app is named before AWT starts, and its icon is rendered from one SVG
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0211 — The app is named before AWT starts, and its icon is rendered from one SVG

## Context

PLAN-0018 H8.11. Under `make desktop` the macOS menu bar and the Dock name
the app `MainKt`, the main class, and the window, the Dock and the packages
use Java's default icon. The owner (2026-11-07) asked for "App Monitor"
everywhere and an original icon: a tile in the mockup's muted blue
(`#4A6FA5`, `docs/screenshots/h8-mockup.html`) with a white mark of a signal
passing between a phone and a window.

Evidence read:

- Zulu 21's `lib/libosxapp.dylib` (`strings`) reads the system properties
  `apple.awt.application.name` and `apple.awt.application.appearance`, and
  the launcher's `APP_NAME_<pid>` (`-Xdock:name`). Like the appearance
  (ADR-0150), the name is read once, when AWT's toolkit starts.
- The Compose Gradle plugin 1.12.2's jar holds no `-Xdock` string: its `run`
  task passes neither a Dock name nor a Dock icon; the packages take the
  bundle's `CFBundleName` and `iconFile`.
- `java.awt.Taskbar` (JDK 9+) sets the Dock's image at run time on macOS
  (`Feature.ICON_IMAGE`); it starts AWT's toolkit.
- Skiko 0.150.2 (on the desktop's classpath) has `org.jetbrains.skia.svg.SVGDOM`,
  which renders an SVG on a raster `Surface`.
- This machine has `iconutil` and `sips` but no `rsvg-convert`, ImageMagick
  or Inkscape; Linux and Windows have neither `iconutil` nor `sips`.

## Options

1. **Name set in `main`, Dock icon through `Taskbar`, files rendered by a
   Gradle task with Skia.** Works under `make desktop` and in the packages,
   on every OS; the ICNS and ICO formats are a few lines each (a header, then
   PNG entries).
2. **`-Xdock:name` and `-Xdock:icon` in `jvmArgs`.** Unknown `-X` options
   stop the JVM on Linux and Windows, and the packages would carry them too.
3. **`iconutil` and a converter installed with Homebrew.** Adds a tool to the
   setup and leaves the ICO to another one; macOS only.

## Decision

Option 1.

- `nameApp()` sets `apple.awt.application.name` to "App Monitor" first thing
  in `main`. `showDockIcon()` sets the Dock's image from `app-monitor.png`
  after the appearance property (ADR-0150), since it starts the toolkit.
  Every window gets the same PNG as its icon.
- `desktop/app/icon/app-monitor.svg` is the source. `./gradlew
  :desktop:app:appIcons` (run by hand, like `titleBarScreenshots`) renders it
  with Skia to `src/main/resources/app-monitor.png` (1024 px), `icon/
  app-monitor.icns` (`ic07` to `ic14`, 32 to 1024 px) and `icon/
  app-monitor.ico` (16 to 256 px). The three files are committed;
  `nativeDistributions` uses the ICNS on macOS, the ICO on Windows and the PNG
  on Linux.
- The mark is drawn for this project: a phone outline, three dots growing
  brighter towards a window outline with a title bar and three list lines,
  white on a rounded tile whose gradient runs from `#5479AF` to `#40659B`
  around the mockup's `#4A6FA5`.

## Consequences

- A change to the icon is an SVG edit and one task; `AppIdentityTest` checks
  that the committed ICNS and ICO hold a PNG for each size and that the window
  PNG is 1024 px.
- Nothing checks that the committed files match the SVG: a hand edit of the
  SVG without `appIcons` goes unnoticed until someone looks at the Dock.
