---
id: ADR-0178
title: The macOS title bar follows a picked theme through JNA
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-03
superseded_by:
---

# ADR-0178 — The macOS title bar follows a picked theme through JNA

## Context

ADR-0150 sets `apple.awt.application.appearance` before the first window:
AWT reads it once, so a theme picked in Settings reaches the title bar, menus
and system dialogs only at the next launch (DEBT-0095). PLAN-0016 H6.2 pays it.
ADR-0150 stays as is: the property still gives the first window the right
appearance, before anything else runs.

Evidence read:

- `javap -constants` on `CPlatformWindow` (ADR-0150): no per-window appearance
  property on the JDK 21 that gets packaged.
- `-[NSApplication setAppearance:]` works on the running app, `nil` going back
  to the system's: `MacAppearanceTest` reads the name back from AppKit after each
  call, and `titleBarScreenshots` a real window's `effectiveAppearance`.
- `java.lang.foreign` is a preview API on JDK 21, the packaged runtime
  (`javap -v java.lang.foreign.Linker`: `PreviewFeature`).
- JNA 5.19.1 is the latest on Maven Central (`maven-metadata.xml`), Apache-2.0
  or LGPL-2.1-or-later at the user's choice (its POM); the jar is 1.9 MB. It was
  on no desktop classpath (`:desktop:app:dependencies`).

## Options

1. **Call AppKit through JNA.** `[NSApp setAppearance:]` on the main thread
   through `objc_msgSend`. One small file; a new dependency, loaded on macOS only.
2. **A small JNI library of our own.** No dependency, but a native build per
   architecture in `make build`, and signing later.
3. **`java.lang.foreign`.** No dependency, needs a preview flag until the
   runtime moves past JDK 21.
4. **A transparent title bar painted by us** (ADR-0150 option 2). Changes the
   window's chrome, the title needs a band of our own.

## Decision

Option 1. `applyMacAppearance` in `desktop/app/MacAppearance.kt` follows
`AppearanceRepository.theme`, off the UI thread, for as long as the window is
open; AppKit runs the call on its main thread (`performSelectorOnMainThread:`).
System sends `nil`. A `LinkageError` is printed and leaves the title bar as it
was, which is ADR-0150's behavior. Off macOS JNA is never loaded.

## Consequences

- Light, Dark and System reach the title bar, menus and system dialogs at once.
- `desktop/app` ships one more jar on every system.
- `MacAppearanceTest` covers the call against AppKit on a Mac with a display
  and skips elsewhere. `titleBarScreenshots` opens a real window and checks its
  `effectiveAppearance`; the picture needs Screen Recording for the process that
  runs it, which the loop's session does not have (DEBT-0124).
- `objc_msgSend` with structs differs on x86-64, so `titleBarScreenshots` runs on
  Apple silicon only. `applyMacAppearance` itself passes pointers and one `BOOL`.
