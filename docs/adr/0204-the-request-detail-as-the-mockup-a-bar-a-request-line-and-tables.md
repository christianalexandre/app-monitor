---
id: ADR-0204
title: The request detail as the mockup, a bar, a request line and key/value tables
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-06
superseded_by:
---

# ADR-0204 — The request detail as the mockup, a bar, a request line and key/value tables

## Context

PLAN-0018 H8.3 narrows ADR-0147's detail and ADR-0153's heading to the
mockup the owner approved on 2026-11-06 (`docs/screenshots/h8-mockup.html`,
`Detail`, `BodySection`, `KV`, `Segmented`). ADR-0147's tabs, the tab kept
across picks and every copied text stay; ADR-0152's search and ADR-0199's
gzip bodies stay.

Evidence read:

- The field test (2026-11-06): the heading repeated the whole URL in body
  text beside "Mock this" and "Send again", the tabs under it, then the
  search field, so the first body line started about 140 dp down, and the
  detail was only usable with the devices pane hidden.
- The mockup: a 38 dp bar with `host › path`, the tabs as a segmented
  control, "Mock", "Replay" and "⋯"; under it the status, the method and
  the URL in small monospace and a meta line (duration, response size,
  device, start time, layer); Summary, Headers and Timing as monospaced
  key/value tables, every other row shaded; each body side with a small
  capital label, its size and `Content-Type`, Text/JSON/Hex as a small
  segmented control and a copy glyph.
- ADR-0120: the replay button warns when the method is not safe. The
  mockup's button only says "Replay".
- `DropdownMenu` is already wrapped by `desktop/designsystem`'s `Choice`
  (ADR-0133: Material only there).
- `HttpExchange` holds no device: the window's sessions do
  (`TrafficState.sessions`, the row's `sessionId`).

## Options

1. **Three design-system components (`Segmented`, `MenuButton`,
   `KeyValueTable`), the bar and the request line in
   `desktop/feature/traffic`, the search behind a glyph.** The bar holds
   what the mockup holds; nothing copied or searched changes.
2. **Keep ADR-0147's `Tabs` and restyle them.** An underline tab row does
   not fit beside the path and the actions in 38 dp.
3. **Put Copy as cURL and Save as issue in the bar as buttons.** Five
   buttons and five tabs do not fit a detail pane at 1280 px.

## Decision

Option 1.

- `desktop/designsystem/Controls.kt`: `Segmented` (bordered, the chosen
  one filled in the primary color; `small` for a body's formats; the role
  given, `Tab` for the tabs so `Role.Tab` tests still find them,
  `RadioButton` for the formats), `MenuButton` (a `GlyphButton` opening a
  `DropdownMenu` of `MenuAction`s, each with a test tag), `KeyValueTable`
  (monospaced keys muted at 170 dp, values beside, a rule between rows,
  even rows on the code background, `query` highlighted).
- The bar: `host › path` (`urlPartsOf`, ADR-0203), the tabs with their
  match counts, "⌕" that shows or hides the search field (it stays shown
  while it holds text; hiding it empties it), "Mock", "Replay" ("Replay:
  runs POST again" for an unsafe method, ADR-0120), "⋯" with "Copy as
  cURL" (not on a connection layer exchange) and "Save as issue" (what
  the list's "Save as issue" saves: the marked rows, else the picked one).
- The request line: the status badge, the method, the whole URL
  selectable with its copy glyph; then `metaOf`: duration, response size
  (its original size when cut) "↓", the device's name when its session is
  in the window, the start time to the millisecond, the layer.
- Summary: error, "Mocked by …", the replay note and link, then a Request
  table (host, path, query, method, scheme) and a Response one (status or
  the failure, duration, body size, `Content-Type`), the connection's bytes
  for a connection layer exchange. Its search counts those tables' values.
- Headers: "Request · N" and "Response · N" labels with the copy glyph, a
  table each, in the order sent. Timing: one table. cURL: label, glyph,
  code block.
- Body: the label row holds size, `Content-Type`, the formats and the copy
  glyph; the rows below are as before, so ADR-0153's jump to a match is
  unchanged.

## Consequences

- The first body line starts under the bar and the two-line request line,
  about 100 dp down instead of 140, with the URL always visible.
- The search field is one click away; ⌘F inside the detail is not wired
  (the list's ⌘F, ADR-0203, is). H8.5's palette may add it.
- Summary no longer has the "Status · Duration · layer" caption: that line
  is now the meta line above every tab, and the tables.
- At 1280 px with the devices pane shown, the bar's tabs with match counts
  leave `host › path` a few characters (`detail-search.png`); the whole URL
  stays on the request line under it. H8.4's 220 dp sidebar gives the
  detail back about 20 dp.
