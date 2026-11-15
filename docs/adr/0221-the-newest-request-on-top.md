---
id: ADR-0221
title: The newest request on top of every request list
status: Accepted
date: 2026-11-15
superseded_by:
---

# ADR-0221 — The newest request on top of every request list

## Context

ADR-0139 A put the live list "newest last". In the field test of `0.1.0`
(2026-11-15), the owner asked for the newest request on top, so the latest
one is always in view, as in app-network-monitor and most network
inspectors (PLAN-0020 H9.1).

The request list reads `TrafficRepository.observeExchanges(filter, limit)`.
That is the first `limit` matches in arrival order, `limit` grown by a page
as the list nears its end. It reads from the live list in memory
(`LiveTraffic`, a `LinkedHashMap` in arrival order) or from the store
(`SearchQuery`, `ORDER BY exchanges.id` through the indexes of ADR-0087 and
ADR-0177). The MCP tools, the HAR export and the benchmarks page through
`searchExchanges`, oldest first, by `after_id`.

A Compose `LazyColumn` with keys keeps its first visible row when rows are
inserted above it. So with the newest on top, a list shown from its top would
keep the old first row and hide each new one above it.

## Options

1. **Newest first in `observeExchanges` only.** Live: the map's reversed
   view. Store: `ORDER BY exchanges.id DESC`. The paged readers stay oldest
   first. The list follows new rows only when shown from its top.
2. **Reverse the rows in the view model.** The limit would then keep the
   oldest N, and the newest would fall off the page once a list passes 200
   rows.
3. **`reverseLayout` on the list.** Bottom-anchored, so the empty space and
   the scrolling start at the bottom. It suits a chat, not a list read top
   down.

## Decision

Option 1.
- `observeExchanges` returns the newest `limit` matches, newest first: the
  live map's `sequencedValues().reversed()` view, and `ORDER BY id DESC` on
  the store. `SearchTest` checks that the descending plan uses the same index
  and sorts no more than the ascending one.
- `searchExchanges` and every `after_id` reader keep arrival order.
- In the window, a list whose first row is at the top scrolls to the new
  first row when rows arrive above it. A list read further down keeps its
  rows still (the list's key anchoring). This replaces ADR-0139 A's "newest
  last"; the rest of ADR-0139 stands.

## Consequences

- "Show more" at the bottom now loads older requests.
- The window and the MCP tools list in opposite orders. The tools page by
  `after_id`, which needs arrival order.
