---
id: ADR-0011
title: Desktop app skeleton with Material, snapshot store and offscreen UI test
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0011 — Desktop app skeleton with Material, snapshot store and offscreen UI test

## Context

PLAN-0001 M0.5 builds `desktop/app`: device list, request list (method, URL,
status, duration) and detail (headers, body as text/JSON/hex) over an
in-memory store, proven by a Compose desktop UI test with fake traffic and a
screenshot. ADR-0005 already places persistent storage in SQLDelight (M5);
this store only has to last until then.

Evidence, checked on 2026-10-21:

- Maven Central: `org.jetbrains.compose.material:material`, `ui-test` and
  `foundation` exist at 1.12.2, the Compose Multiplatform version in the
  catalog. `org.jetbrains.compose.material3:material3` is versioned apart:
  latest stable 1.9.0, newer ones `1.10.0-alpha*` to `1.13.0-alpha02` only.
- The Compose Gradle plugin 1.12.2 marks its `compose.material3`,
  `compose.uiTest` and similar accessors `@Deprecated("Specify dependency
  directly")` (`ComposePlugin$Dependencies`, javap); `compose.desktop.currentOs`
  is not deprecated.
- `ui-test-desktop` 1.12.2 sources: `androidx.compose.ui.test.v2.runDesktopComposeUiTest(width, height)`
  renders offscreen with no window, and `SemanticsNodeInteraction.captureToImage()`
  returns the rendered pixels. The v1 entry points are deprecated.
- kotlinx-serialization-json 1.12.0 (Apache-2.0) re-encodes numbers when
  pretty-printing: the body `-1.5e3` came back as `-1500.0` (observed in
  `BodyFormatTest`).

## Options

1. **Material 3** — current design system, but only 1.9.0 is stable, three
   minors behind the rest of Compose.
2. **Material (2) 1.12.2** — same version line as every other Compose
   artifact; enough components for lists, buttons and typography.
3. **Store as a mutable model observed field by field** vs **one immutable
   `TrafficState` in a `StateFlow`, changed by pure functions per envelope** —
   the second tests without a UI or a server and gives the UI consistent
   snapshots.
4. **JSON view: re-encode the parsed tree** vs **validate with a parser, then
   re-indent the original text** — only the second shows what the device sent.

## Decision

Material 2 at the Compose version (option 2), dependencies declared in the
catalog rather than through the deprecated plugin accessors. `TrafficStore`
holds a `StateFlow<TrafficState>`; `TrafficState.received` decodes one
`network` envelope and returns the next snapshot. An event for an unknown
`request_id` creates the request, so nothing the device sent is hidden. JSON
bodies are validated with kotlinx-serialization and re-indented
token by token from the original text. The UI test starts a real
`AppMonitorServer`, replays the fixtures with `tools/fake-client` (whose
`replay`, `Target` and `Fixtures` become public for that), renders the
resulting state offscreen at 1280×800 and writes
`desktop/app/build/screenshots/app.png`.

## Consequences

- The UI test needs no display: it runs headless on CI runners.
- Moving to Material 3 later is a dependency and import change, decided when
  a stable release matches the Compose version.
- Each envelope copies the request list: fine for a debug session, replaced
  by SQLDelight queries in M5 (ADR-0005).
- WebSocket frames are not shown yet (DEBT-0003).
