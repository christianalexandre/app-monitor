---
id: ADR-0208
title: A body opens as JSON whenever it parses, its lines wrap and long string values are cut
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0208 — A body opens as JSON whenever it parses, its lines wrap and long string values are cut

## Context

PLAN-0018 H8.8. The owner (2026-11-07): the default view is JSON whatever the
headers say. Until now `defaultFormat` (`BodyFormat.kt`) picked JSON only when
the `Content-Type` held `json` and the text parsed as strict JSON, so a JSON
body sent as `text/plain`, or with no `Content-Type`, opened as one long line
of text. Every body chunk also sat in a `horizontalScroll` with
`softWrap = false`, so a long value (a token, a base64 image) made the
developer scroll sideways to read the next key.

Known constraints:

- JSON is re-indented from the device's text in one pass, never re-encoded
  (SDD 01), and the rendered text is split into chunks (ADR-0029).
- The search inside the request counts and walks what the rendered chunks
  hold (ADR-0152, ADR-0153).
- Copy, cURL, a replay, the MCP tools and the HAR export read the bytes as
  sent (ADR-0199), not the rendering.

## Options

1. **Keep the `Content-Type` rule.** The owner asked otherwise; a mislabelled
   JSON body stays unreadable.
2. **JSON whenever the text parses as strict JSON; in the JSON view only,
   lines wrap and a string value over 200 characters is cut.** The cut is done
   while indenting, on the value's text as sent between its quotes, so it
   costs nothing more than the pass already made. It never falls inside an
   escape or between a surrogate pair's halves, and keys are never cut. The
   cut values are kept with the rendering, so the search can say how many
   matches it leaves out.
3. **JSON whenever it parses, and a collapsible tree.** More than the task
   asks, and a tree of a 16 MiB body would not fit ADR-0029's chunks.

## Decision

Option 2. `defaultFormat` no longer reads the headers: strict JSON first,
then UTF-8 text, then hex. A cut value shows its first 200 characters and
ends with `… (N more)` inside its quotes, `N` counting the characters left
out of the text as sent. Text, Hex, Copy and cURL keep the whole value. The
match count adds "· N in cut values" when matches fall in a cut part; one
that starts before the cut and ends after it counts as cut. Text and Hex
still scroll sideways, as their columns matter.

## Consequences

- A JSON body opens indented whatever its headers, and the JSON view never
  needs a sideways scroll.
- A body that happens to parse as JSON but is not meant as JSON (`"text"`,
  `42`) opens as JSON; the Text / JSON / Hex control is one click away.
- A match jumped to with "↓" is placed by its line number times the line
  height. Wrapped lines above it in the same chunk push it lower than that,
  so it may land a few lines under the expected spot (DEBT-0131).
- The view's own `… (N more)` text is searchable like the rest of the view.
