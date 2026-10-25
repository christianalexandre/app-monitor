---
id: ADR-0146
title: A toolbar over the panes, request rows with a status badge, the URL cut in its middle
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0146 — A toolbar over the panes, request rows with a status badge, the URL cut in its middle

## Context

ADR-0140 G asks for three panes with a toolbar holding Record/Stop,
Clear, Save, filters and search; a request row with a colored status
badge, method, URL truncated in the middle, time and duration; empty
states. PLAN-0013 H3.6 does the toolbar, the row and the empty states;
the detail tabs (H3.6b) and the filter sheet (H3.6c) follow.

On `dev` at `f69335a`, the devices pane started with three rows of
buttons (run picker, Issues, Mocks, Breakpoints, Settings), the request
pane with two more (Record, Save, Clear; Benchmarks, Export HAR, Save as
issue) and a filter bar; a row showed the method, the URL cut at its
end, the status as plain text and the duration.

Evidence read:

- The owner's references (`AGENTS.md`, "Desktop architecture
  follows known references"): `mibo-smart-app`'s
  `core/ui/designsystem/.../component/MiboTag.kt` (a short label on a
  translucent background of its tone's color, one `MiboTagTone` enum),
  `MiboEmptyState.kt` (a centred message in place of an empty result)
  and `MiboTopBar.kt` (one bar, actions in a `RowScope`).
- Compose Multiplatform 1.12.2's `ui-text-desktop` has
  `TextOverflow.MiddleEllipsis` (`javap` on `TextOverflow$Companion` in
  the Gradle cache), but the desktop draws it as an end ellipsis: the
  first `mocked.png` of this change showed `https://api.example.c…` with
  `maxLines = 1` and with `softWrap = false`.
- At 1280 px, a toolbar with the run picker, Record, Save, Clear, Save
  as issue, the search field and the three filters takes about 1 000 px
  (`live.png`); the six links to other screens do not fit beside them.

## Options

1. **One toolbar for what acts on the list; the links to other screens
   at the foot of the devices pane; a `Badge` with tones; a measured
   middle cut.** The toolbar is what ADR-0140 G lists; the links keep
   their tags and labels.
2. **Everything in the toolbar.** Does not fit at 1280 px without
   overflow menus, which the design system lacks.
3. **`TextOverflow.MiddleEllipsis`.** No code, but it cuts the end on the
   desktop, where the path's last segment is what tells requests apart.

## Decision

Option 1.

- **Toolbar** (`Toolbar` in `desktop/designsystem`, as `MiboTopBar`'s
  actions row): run picker, Record / "Stop recording", Save, Clear (live
  list only), "Save as issue", search, method, status, "Failed only".
  Save's and the export's notes go under it.
- **Links**: Issues, Benchmarks, Mocks, Breakpoints, Export HAR,
  Settings, two to a row under the devices.
- **Row**: checkbox, status `Badge`, method, URL, "mocked" and "replay"
  badges, start time `HH:mm:ss.SSS` in the machine's zone, duration.
  The request pane takes 1.5 the width of the detail.
- **Badge** (as `MiboTag`): `BadgeTone` Neutral, Success, Info, Warning,
  Error; the text in the tone's color, the background the same color at
  16 %; `AppColors` gains `success`, `info`, `warning`, `neutral`, each
  with a light and a dark value, Error reusing `error`. A status takes
  Success for 2xx, Info for 3xx, Warning for 4xx, Error for 5xx, a
  failure or a gRPC status other than 0, Neutral while pending, for 1xx
  or a socket.
- **`MiddleEllipsisText`**: measures with `rememberTextMeasurer` and
  keeps the longest start and end around "…" that fit, by binary search
  (`middleCut`); the semantics keep the whole text, so tests and screen
  readers see the URL.
- **`EmptyState`** (as `MiboEmptyState`): a title and a hint centred in
  the pane. "No requests yet" says live, recording or stored run apart;
  "No requests match" asks to change the search or filters; the detail
  says "Select a request".

## Consequences

- Each visible request row measures its URL about `log2(length)` times
  when its text or width changes; rows are laid out lazily.
- The filters move into a sheet in H3.6c; the toolbar then holds a
  "Filters" button instead of the three choices.
- SDD 01 and SDD 12 say where each button sits now.
