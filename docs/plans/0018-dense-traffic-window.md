---
id: PLAN-0018
title: A dense traffic window, as the owner's mockup
status: Done # Draft | Active | Done | Abandoned
milestone: H8
started: 2026-11-06
finished: 2026-11-07
---

# PLAN-0018 — A dense traffic window, as the owner's mockup (H8)

## Goal

The traffic window reads like a desktop tool: the endpoint is always visible in the list, the
detail is readable without hiding the devices pane or widening it, and every screen is reached
from a ⌘K palette. The window matches the mockup the owner approved on 2026-11-06,
[`docs/screenshots/h8-mockup.html`](../screenshots/h8-mockup.html) (light, dark and the palette in
`h8-mockup-light.png`, `h8-mockup-dark.png`, `h8-mockup-palette.png`).

The owner asked for it after a field test in a real app (2026-11-06). The SDKs connected
from a simulator and an emulator and their traffic was right. The window was not:

- text sizes are Material's mobile defaults (`Typography()`, 16 sp body, spaced button labels);
- a request row cuts its URL in the middle, so `api.exam…/items?page=2` hides the path;
- the detail repeats the whole URL as a large title, and was only usable with the devices pane
  hidden and the detail dragged wide.

The mockup takes the visual language of a prototype of the owner's and the row and detail
ideas of `app-network-monitor`'s client
(`christianalexandre/homebrew-app-network-monitor-client`, `SidebarView.swift` `LogRow`,
`LogDetailView.swift`). The ⌘K palette is new scope: the owner approved it on 2026-11-06.

Nothing private goes into the repository (owner, 2026-11-06): no name, host, file or data of the
field test app, of the reference app or of the prototype (AGENTS.md, "Nothing private"). ADRs cite
"the owner's reference app" with the pattern in their own words, and screenshots come from the
samples or invented data.

Only the window changes. The store, the MCP tools, the proxy and the SDKs do not. Each task
updates the SDD it changes in its own PR (ADR-0094). The `Hold` before M10 stays.

## Tasks

- [x] **H8.1 Desktop type scale and tokens.** (#386, ADR-0202) `desktop/designsystem` stops using Material's
  `Typography()`. It gets its own scale, as the owner's reference app does, with
  explicit sizes, line heights and letter spacing, and no spaced button labels:
  - body 12.5–13, secondary 11.5, caption 10.5–11, section label 10 in capitals, code 11.5
    monospace;
  - the mockup's neutral grays per layer (sidebar, list, content), its muted blue for
    selection only, and its status tones;
  - radius 6;
  - the heights the mockup uses: status bar 24, detail bar 38, title bar 40.
  The new roles and tokens are in the gallery. The screens pick up the scale without a layout
  change.
  ADR in this task: it supersedes ADR-0149's values and records the scale.
  Accept: `./gradlew :desktop:designsystem:test` with `gallery-light.png` and
  `gallery-dark.png` read; `make designsystem-check`; `./gradlew :desktop:app:test` green with
  the screenshots read.
- [x] **H8.2 Two-line request rows.** (`feat/two-line-request-rows`, ADR-0203) Each row is in two lines:
  - first line: method, **path** and start time;
  - second line: status badge, host (or the failure's message), the device's platform glyph
    and the duration (warning tone over 2 s).
  When the row is narrow, the query is cut first, then the start of the path, so the path's
  end stays visible. The mark checkbox shows on hover or once marked. Marked rows bring a bar
  with "Save as issue" and "Save".
  The list's header holds the URL filter (⌘F), the status classes (All, 2xx, 3xx, 4xx, 5xx,
  Failed) and the count. That replaces the toolbar's search and status choices. The
  "mocked" and "replay" badges stay.
  ADR in this task: it narrows ADR-0146's row and toolbar.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a test that a long
  path with a query keeps the path's end in the row's text; `app.png` and `filters.png` read.
- [x] **H8.3 The detail, as the mockup.** (`feat/detail-as-the-mockup`, ADR-0204)
  - A 38 dp bar: `host › path` on the left, the tabs (Summary, Headers, Body, Timing, cURL) as
    a segmented control, then "Mock", "Replay" and a "⋯" menu for the rest (Copy as cURL,
    Save as issue).
  - Under it: the status, the method and the whole URL in small monospace, selectable, and a
    meta line (duration, response size, device, start time, capture layer).
  - Body: each side has a small label, its size and `Content-Type`, the Text/JSON/Hex choice
    as a small segmented control and a copy glyph. JSON is picked when the text parses, as
    today. Gzip bodies keep ADR-0199's behavior.
  - Summary and Headers: key/value tables in monospace.
  - The search inside the request (ADR-0152) moves behind a search glyph in the bar.
  ADR in this task: it narrows ADR-0147 and ADR-0153's heading.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with the `detail-*.png`
  screenshots read; each tab's copied text unchanged (existing tests).
- [x] **H8.4 Sidebar, title bar and status bar.** (`feat/sidebar-title-and-status-bars`, ADR-0205, DEBT-0130:
  no counts, the store has no count query; the other screens sit in an "Actions" menu until H8.5;
  the method filter joins the list header and the filter sheet goes)
  - A 220 dp sidebar replaces the devices pane, the run picker and the filter sheet's hosts.
    It has three sections:
    - DEVICES: all devices, then each device with a connected dot, app and OS, and its count;
    - TRAFFIC: Live, the recording, the stored runs, Saved;
    - HOSTS: one per host, with its count; a click shows or hides it.
  - ⌘B hides the sidebar, and the hidden state is kept (ADR-0153's `ui.panes`).
  - The title bar holds Record/Stop, Clear and "Actions ⌘K".
  - A 24 dp status bar shows: listening and its port, devices connected, recording and its
    count, MCP, proxy, events dropped, and the storage and retention warnings.
  - The bottom row of links (Issues, Benchmarks, Mocks, Breakpoints, Export HAR, Settings)
    leaves for the palette (H8.5) and a gear in the sidebar.
  - The list keeps its drag (ADR-0151).
  Approve, Disconnect and Reconnect stay on a device's row.
  ADR in this task: it supersedes ADR-0151's devices pane and the links' place.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with `devices.png`,
  `devices-hidden.png`, `panes.png`, `runs.png`, `recording.png` and `disconnected.png` read.
- [x] **H8.5 The ⌘K palette.** (`feat/command-palette`, ADR-0206; Compare runs kept in Open, ⌘R ⌘S ⌘E ⌘, the only new shortcuts) A palette over the window, as the mockup, with four groups:
  - Requests: endpoints matching what is typed, one per method and path;
  - Actions: Record/Stop, Save, Save as issue, Mock, Replay, Copy as cURL, Export HAR, Clear;
  - Open: Issues, Benchmarks, Mock rules, Breakpoints, Settings;
  - Devices: show one.
  ↑ and ↓ move, ↵ runs, Esc and ⌘K close. Every action the palette runs is one a button or a
  menu already runs: it adds a way in, not a feature. The keyboard shortcuts it lists work
  without it. The components are in `desktop/designsystem` and the gallery.
  ADR in this task.
  Accept: `./gradlew :desktop:designsystem:test :desktop:feature:traffic:test
  :desktop:app:test`, with a test that types, moves and runs an action and one that opens a
  screen; a `palette.png` screenshot (and its `-dark` twin) read.
- [x] **H8.7 Replay answers on every tab.** (`feat/replay-note-on-every-tab`, ADR-0207; reproduced: the replay was sent from every tab, `make e2e-ios` and the Body tab's `ReplayTest`, only its note was missing) The owner's first look at the window (2026-11-07):
  "Replay does not seem to do anything". The desktop sends `ReplayRequest`, but its note ("Sent
  to the app…", "Not sent: …", a refusal) only shows on the Summary tab, and the replayed call
  lands at the end of the list unpicked.
  - The note shows on every tab, as the copy note does, at the detail's bottom.
  - Once the replayed call arrives, the note says so and offers "Open", which picks it.
  - Reproduce first: on a simulator or an emulator, check that a Replay from the Body tab
    reaches the app and comes back as a replay. If it does not, fix that in this task.
  ADR in this task.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a test that a
  Replay from the Body tab shows the note and that "Open" picks the replayed call;
  `make e2e-ios` or `make e2e-android` green.
- [x] **H8.8 JSON first, and long values cut.** (`feat/json-first-long-values-cut`, ADR-0208, DEBT-0131) The owner (2026-11-07): the default view is
  JSON whatever the headers say.
  - A body opens as JSON whenever its text parses as strict JSON, whatever its `Content-Type`.
    Otherwise it opens as Text when it is UTF-8, and as Hex for anything else.
  - The JSON view never scrolls sideways: its lines wrap.
  - In the JSON view only, a string value over 200 characters is cut and ends with
    `… (N more)`. Text, Hex, Copy and cURL keep the whole value.
  The search inside the request finds what the view shows. Its count says when a match is in
  a cut part.
  SDD 01's "open as JSON when the `Content-Type` says JSON" changes in this task.
  ADR in this task.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`:
  - `text/plain` and missing-`Content-Type` JSON bodies open as JSON;
  - a 5,000-character value is cut in the view and whole in Copy;
  - `detail-body.png` is read with no sideways scroll.
- [x] **H8.9 Vector icons and the sidebar's refinements.** (`feat/vector-icons`, ADR-0209; the palette's entries got icons too, and the host filter a `noHost` flag so every host can be hidden) The window's icons are Unicode
  glyphs (`◧`, `▯`, `⌕`, `⧉`…). Some are missing from the font (the "show sidebar" toggle draws
  a broken square) and others are tiny.
  - `desktop/designsystem` gets vector icons (`ImageVector`, 16 × 16 strokes) drawn from the
    mockup's SVGs: sidebar, live, record, saved star, globe, check, search, copy, gear, trash,
    save, issue, chart, mask, pause, export, replay, ellipsis, bolt, phone, android and filter.
    The icons are in the gallery, and every glyph button in the window uses them.
  - Sidebar rows:
    - Live has the live icon;
    - the recording has a red record icon;
    - a stored run has the record outline;
    - Saved has the star.
  - A host row shows a check in the accent color when the host is shown, and the globe in the
    tertiary color when it is hidden.
  - The HOSTS label has an icon button that hides every host, and shows them all again when
    every host is hidden.
  - The copy and search icons in the detail are at least 14 dp, as the bar's other icons are.
  ADR in this task.
  Accept: `./gradlew :desktop:designsystem:test :desktop:feature:traffic:test
  :desktop:app:test`, with the gallery and `devices.png`, `devices-hidden.png`, `filters.png`
  and `detail-*.png` read; a test for hosts all off and back on.
- [x] **H8.10 A fuller title bar.** (`feat/fuller-title-bar`, ADR-0210; tools fold under 760 dp, not at the 1000 dp minimum, where they fit with 450 dp left for the title; a tool the palette hides is dimmed) The owner (2026-11-07): besides the palette, the title bar
  holds the actions and the tools.
  - After Record/Stop and Clear, icon buttons for Save (⌘S), Export HAR (⌘E), Issues,
    Benchmarks, Compare runs, Mock rules, Breakpoints and Settings (⌘,), then
    "Actions ⌘K".
  - Each button has a tooltip with its name and its shortcut. Each runs what the palette's
    entry runs.
  - Under a narrow window, the tools fold into the "⋯" menu, so the title never overlaps
    them.
  ADR in this task: it narrows ADR-0205's title bar.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a test that each
  button opens what the palette opens; `app.png` read at 1280 and at the window's minimum
  width.
- [x] **H8.11 The app's name and icon.** (`feat/app-name-and-icon`, ADR-0211; the Dock icon not seen in a screenshot, screen capture denied to the loop: left to H8.6's look)
  - The window, the Dock and the macOS menu bar say "App Monitor" under `make desktop` too.
    Today the menu says `MainKt`.
  - The app gets an original icon:
    - a rounded-square tile in the mockup's muted blue, with a white mark of a signal passing
      between a phone and a window;
    - its SVG source is in the repository;
    - the PNG, ICNS and ICO built from it are wired into the window and into
      `nativeDistributions` for macOS, Windows and Linux.
  - Nothing in it comes from another product's icon.
  ADR in this task.
  Accept: `make desktop` shows "App Monitor" in the menu bar and the icon in the Dock,
  screenshot read; `./gradlew :desktop:app:packageDmg` builds with the icon; `make check`.
- [x] **H8.12 Body first, and the gear for Settings.** (`feat/body-first-and-settings-gear`, ADR-0212; the gear was the mockup's sun, redrawn as a cog everywhere it shows; the other icons matched) The owner's second look (2026-11-07).
  - Picking a request opens its detail on the Body tab, not Summary.
  - A tab the developer picks still stays across picks, as ADR-0147 has it.
  - The title bar's Settings button draws the gear icon, not the light/dark one.
  - Check that every other title bar button's icon matches its name.
  ADR in this task: it narrows ADR-0147's first tab.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a test that a first
  pick opens Body and a picked tab survives the next pick; `app.png` read.
- [x] **H8.13 One title bar, the window's own (macOS).** (`feat/macos-unified-title-bar`, ADR-0213, DEBT-0132; AppKit took the properties in a real window, the buttons end at 61 pt inside a 72 dp inset; the drag, the double click and the buttons' 6 dp offset left to H8.6) The owner's second look (2026-11-07): the
  actions belong up beside "App Monitor", in the window's title bar. Today they sit in a second
  row under it.
  Option (b), picked by the owner on 2026-11-07 (PR #401): macOS only, on any JDK. The app
  does not move to the JetBrains Runtime, whose JBR API is on the classpath but whose classes
  Zulu 21 lacks.
  - On macOS the window sets AWT's `apple.awt.fullWindowContent` and
    `apple.awt.transparentTitleBar`, hides the system title text, and the traffic row is drawn
    in the title bar's place. Left to right:
    - the system's window buttons, with their space kept;
    - the sidebar toggle;
    - "App Monitor — <run>" centred;
    - Record/Stop, Clear, the tools and "Actions ⌘K".
  - The bar's empty parts drag the window (Compose's `WindowDraggableArea`), and a double click
    there zooms it, as the system's title bar does. The buttons take clicks.
  - ADR-0178's appearance still applies.
  - Windows and Linux keep today's native title bar and the row under it. A DEBT records it,
    with the trigger "the owner runs the desktop on Windows".
  - The window's minimum width and the "⋯" fold (ADR-0210) still hold.
  ADR in this task: it narrows ADR-0205 and ADR-0210.
  Accept: `./gradlew :desktop:app:test`, with a test that the macOS properties are set only on
  macOS; `make check`. The loop cannot read a real window (DEBT-0124): the bar, its drag and
  its double click are for the owner's look in H8.6.
- [x] **H8.6 The window in use.** (`test/live-screenshots-after-the-sidebar`; the owner's third look,
  2026-11-07: "ficou melhor". Simulator screenshots only: the emulator was closed.) The owner's task, run with the owner and not by `/next`.
  The owner's first look (2026-11-07) became H8.7 to H8.11, the second H8.12 and H8.13; this
  task closes after them, with the owner's next look.
  `./gradlew :desktop:app:liveScreenshots` from the field test app or the samples, on a
  simulator and an emulator. The owner compares the window with the mockup, and the
  differences the owner names are fixed in this task. The README's desktop screenshot is
  replaced.
  Accept: `docs/screenshots/<name>-*.png` regenerated from a live session; `make check` green.

- [x] **H8.R1 A hidden host leaves the others shown.** (`fix/hidden-hosts-denylist`, ADR-0214) The H8
  milestone review's finding 1: hiding a host in the sidebar picked every other one, so a host
  seen after that, and every row without a host, left the list while the sidebar showed them
  checked. The filter lists the hidden hosts instead.
  Accept: `./gradlew :desktop:storage:test :desktop:feature:traffic:test`, with a test that a
  host seen after one was hidden shows, and `FilterMatchTest` running the hidden hosts on the
  store and the live list.

## Outcome

### H8 milestone review

`code-review` at `medium` of `c293236..7eafe78` (#386 to #403): a static read of the diff, 118
files, no Gradle test run by it. Nine findings, one that normal use hits, each then read in the
code here. Findings 2 to 7 are correctness findings left open, so this PR waits for the owner.

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | Hiding a sidebar host picks every other one, so a host seen later and rows without a host leave the list. | Yes: hide a noisy host, then use the app; read in `withHost` and `Search.kt` | H8.R1 |
| 2 | `sendAgain` sends nothing, silently, when the pick changes before its first read; two quick clicks send twice. | No: a pick inside one store read | DEBT-0133 |
| 3 | A Replay from a stopped recording awaits its arrival in that recording, so the note never says it came back. | No: the call is sent, only the note's second state is lost | DEBT-0133 |
| 4 | `replaysOf` reads up to 10,000 exchanges at each store change while a replay is awaited. | No: a cost, not measured | DEBT-0133 |
| 5 | The header's `more` reads `limit` outside its combine, and says `200+` for exactly one page. | No: one wrong `+` | DEBT-0133 |
| 6 | A `text/plain` `42` opens as JSON, and every UTF-8 body goes through the parser. | The first half is ADR-0208's choice (owner, 2026-11-07: JSON whenever it parses), rejected; the cost is DEBT-0133 | DEBT-0133 |
| 7 | `indentJson` copies a string token whole, so a huge object key passes `MAX_RENDERED_CHARS`. | No: a crafted key | DEBT-0133 |
| 8 | `contentTypeOf` takes the last `Content-Type` line, the decoding the first. | No: a repeated header | DEBT-0133 |
| 9 | `TrafficState`'s KDoc names the filter sheet that H8.4 removed. | No: a comment | Fixed in H8.R1 |

- **Shipped**: H8.1 the type scale and palette (#386). H8.2 two-line rows and the list header
  (#389). H8.3 the detail as the mockup (#390). H8.4 sidebar, title and status bars (#391).
  H8.5 the ⌘K palette (#392). H8.7 the replay's note on every tab (#394). H8.8 JSON first, long
  values cut (#395). H8.9 vector icons and every host hidden (#396). H8.10 the title bar's tools
  (#397). H8.11 the app's name and icon (#398). H8.12 Body first and the cog (#400). H8.13 the
  macOS title bar (#402). H8.6 the window in use, live screenshots (#403). H8.R1 hidden hosts
  listed (this PR). Plan PRs #393, #399 and #401; #387 and #388 kept private names out.
- **Decisions**: ADR-0202 to ADR-0214.
- **Debt**: Created DEBT-0130 (no counts), DEBT-0131 (a jumped match in wrapped JSON),
  DEBT-0132 (two title bars on Windows and Linux), DEBT-0133 (this review). None paid.
- **Deviations**: the owner's looks added H8.7 to H8.13 and moved H8.6 last; H8.4 kept an
  "Actions" menu until H8.5; H8.13 took option (b), macOS only, picked by the owner (#401); H8.6
  had simulator screenshots only, the emulator closed. H8.R1 added by the review.
- **Lessons**: the owner's looks at the real window found what tests and screenshots did not
  (a missing glyph, the Settings icon, the replay's note); a plan that changes a window should
  put the owner's first look after its second or third task, not at its end.
