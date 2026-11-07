---
id: ADR-0205
title: A sidebar, a title bar and a status bar around the traffic, the other screens behind "Actions"
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-06
superseded_by:
---

# ADR-0205 — A sidebar, a title bar and a status bar around the traffic, the other screens behind "Actions"

## Context

PLAN-0018 H8.4 puts the traffic window in the frame of the mockup the owner
approved on 2026-11-06 (`docs/screenshots/h8-mockup.html`, `Sidebar`,
`StatusBar` and the 40 px title bar). It replaces ADR-0151's devices pane
and the row of links ADR-0151 put along the window's bottom. ADR-0151's
drags, ADR-0153's `ui.panes` and ADR-0203's list header stay.

Evidence read:

- The mockup's sidebar is 220 px with three sections (DEVICES, TRAFFIC,
  HOSTS), each row a dot or glyph, a label, a sub line and a count; a host
  row turns the host off and on; MCP and a gear sit at its foot. ⌘B hides it.
- The mockup's title bar holds the sidebar toggle, "App Monitor — <what is
  shown>", Record or Stop, Clear and "Actions ⌘K". Its status bar is 24 px:
  listening and its port, devices connected, the recording, MCP, the proxy
  and, on the right, what was dropped.
- The store has no count query: the window reads exchanges a page at a time
  and the hosts as a list (`TrafficRepository.observeHosts`), and H8 leaves
  the store alone (PLAN-0018 *Goal*). ADR-0203 counts the list's rows for the
  same reason.
- The store's host filter keeps the hosts picked, none picked keeping all
  (`ExchangeFilter.hosts`, ADR-0148); it has no "every host but these".
- The palette that takes the other screens is H8.5; until it lands they
  must stay one click away.

## Options

1. **Sidebar, title bar and status bar as the mockup; the other screens in
   an "Actions" menu on the title bar until the palette; no counts.** Every
   screen stays one click away and nothing in the store changes.
2. **The same with the counts, from new grouped store queries.** As the
   mockup, but a store change H8 leaves out.
3. **The same with counts of the rows read.** Wrong past one page and for
   every device but the one picked.

## Decision

Option 1.

- **Design system**: `TitleBar` (`BarHeight.title`, the title bar layer)
  replaces `Toolbar`; `StatusBar` is `BarHeight.status` tall on its layer;
  `SidebarSection`, `SidebarItem` (dot, label, sub line, trailing slot;
  selected, hovered, dimmed) and `StatusDot`; all in the gallery.
- **Sidebar**, 220 dp by default, dragged 220–400 dp, hidden from the title
  bar's toggle or ⌘B (Ctrl+B off macOS), the hidden state kept in
  `ui.panes` (`PaneLayout.devicesWidth` defaults to 220):
  - DEVICES: "All devices", then each session with a green dot while
    connected, "app version · OS" and "· disconnected" or "· refused until
    Reconnect"; what it dropped; Disconnect under a connected device;
    a refused device with Reconnect.
  - TRAFFIC: Live, every recording newest first (the running one with a red
    dot), then Saved. The shown recording has a "✎" that renames it in place.
    The runs dialog goes.
  - HOSTS: one row per host; a click hides it (picks every other host) or
    shows it (none picked once all are shown again). The last host shown
    cannot be hidden.
  - A gear at its foot opens the settings.
- **Title bar**: the toggle, "App Monitor — <run>", Record or Stop and Clear
  on the live list, then "Actions ▾": Save and "Save as issue" for the
  picked row (no marks), Compare runs (two recordings or more), Issues,
  Benchmarks, Mocks, Breakpoints, Export HAR, Settings. H8.5's palette takes
  the menu's place.
- **Status bar**: a dot and the server's status, the devices connected,
  "Recording since HH:mm" or "Not recording", MCP and the proxy as "MCP
  127.0.0.1:47475", "Proxy off"…, the storage and retention warnings, then
  on the right "Mocks (N to confirm)" and "Breakpoints (N held)" while they
  have something waiting, and "N dropped" (the sessions' evicted, oversized
  and lost events, red above zero).
- **Method filter** joins the list header beside the URL filter; the filter
  sheet goes, its hosts being the sidebar's.
- No count in the sidebar or for the recording: DEBT-0130.

## Consequences

- A host seen for the first time while another is hidden is hidden too,
  until clicked: the store keeps the hosts picked, not the ones hidden.
- Mocks and breakpoints waiting for the owner show in the status bar, as
  they did in ADR-0151's row; the other screens cost one more click until
  H8.5.
- ADR-0151 is superseded: its drags and the window's minimum size are
  restated here, its devices pane and bottom links are gone.
- SDD 01 and SDD 03 describe the frame.
