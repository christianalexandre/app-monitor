---
id: PLAN-0018
title: A dense traffic window, as the owner's mockup
status: Active # Draft | Active | Done | Abandoned
milestone: H8
started: 2026-11-06
finished:
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
- [ ] **H8.4 Sidebar, title bar and status bar.**
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
- [ ] **H8.5 The ⌘K palette.** A palette over the window, as the mockup, with four groups:
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
- [!] **H8.6 The window in use.** The owner's task, run with the owner and not by `/next`.
  `./gradlew :desktop:app:liveScreenshots` from the field test app or the samples, on a
  simulator and an emulator. The owner compares the window with the mockup, and the
  differences the owner names are fixed in this task. The README's desktop screenshot is
  replaced.
  Accept: `docs/screenshots/<name>-*.png` regenerated from a live session; `make check` green.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
