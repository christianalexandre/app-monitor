---
id: ADR-0203
title: Two-line request rows that keep the path's end, and the list's own header
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-06
superseded_by:
---

# ADR-0203 — Two-line request rows that keep the path's end, and the list's own header

## Context

PLAN-0018 H8.2 narrows ADR-0146's request row and toolbar to the mockup the
owner approved on 2026-11-06 (`docs/screenshots/h8-mockup.html`,
`RequestRow` and `RequestList`). ADR-0146 is not reopened: the badge tones,
the "mocked" and "replay" badges, the empty states and the toolbar's other
buttons stay.

Evidence read:

- The field test (2026-11-06): ADR-0146's middle cut showed
  `api.exam…/items?page=2`, so the host's end and the path's start were
  gone and the query, the least useful part, stayed.
- The mockup's row draws the path right-to-left with an end ellipsis and the
  query with a much higher shrink factor, so the query goes first and then
  the path's start; the host moves to the second line.
- The mockup's header counts the rows it holds. The window reads the list a
  page at a time (`TrafficRepository.PAGE_SIZE`, 200, SDD 01) and the store
  has no count query; H8 changes only the window (PLAN-0018 *Goal*).
- Key events in Compose reach a modifier only through the focused node; the
  window had no keyboard shortcut before this task.
- The design system has no icon set.

## Options

1. **Two lines; a measured cut that drops the query, then the path's start;
   the URL filter and status classes over the list; a bar for marked rows.**
   As the mockup, in the design system's text roles.
2. **Keep ADR-0146's one line, cut at the start.** Keeps the path's end, but
   the host, status and duration still fight for one line at 360 dp.
3. **A total count from a new store query.** Exact, but it changes the store,
   which H8 leaves alone, for a number the pages already bound.

## Decision

Option 1.

- **Row**, two lines: the method, the path with its query muted (`PathText`)
  and the start time to the millisecond (always shown; ADR-0146's 560 dp
  rule goes); then the status badge, the host or the failure's message, the
  "mocked" and "replay" badges, the device's platform as a word (Android,
  iOS, JVM: no icon set) and the duration, in the warning tone over 2 s.
  The mark checkbox keeps its place and shows on hover or once marked.
- **`pathCut`** (design system): the whole path and query when they fit;
  else the path and the longest start of the query before "…"; else "…" and
  the longest end of the path. Binary search, as `middleCut`; the row's
  semantics keep the whole URL.
- **Header** over the list: "Filter by URL" with ⌘F (Ctrl+F off macOS; both
  work everywhere), the status choices All, 2xx, 3xx, 4xx, 5xx, Failed
  (`SegmentButton`, one at a time, setting the filter's one class or
  "failed only") and the count, "N requests", or "N+ requests" while the
  last page read is full.
- **⌘F**: the traffic screen's root is focusable and takes focus at start,
  so a key event reaches it while no field holds focus.
- **Marked rows** bring a bar under the list: "N marked", "Save as issue"
  and Save (live list only). The toolbar's Save and "Save as issue" act on
  the picked row and hide while rows are marked.
- **Filter sheet** keeps the method and hosts; the status classes and
  "Failed only" leave it, and "Filters (N)" counts only those two.

## Consequences

- 1xx has no choice in the header; no filter for it was asked for.
- The count is exact up to one page; past it, "+" says more may be stored.
- H8.4 moves the toolbar's remaining buttons to the title bar and the hosts
  to the sidebar; H8.5 reuses the shortcut check for ⌘K.
- SDD 01 and SDD 09 say where the search and status classes sit now.
