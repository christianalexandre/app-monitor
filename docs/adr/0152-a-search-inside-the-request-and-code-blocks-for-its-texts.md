---
id: ADR-0152
title: A search inside the request, header rows, and code blocks with JSON colors
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-26
superseded_by:
---

# ADR-0152 — A search inside the request, header rows, and code blocks with JSON colors

## Context

ADR-0147 put the request detail in tabs with plain text: headers as
`Name: value` lines, bodies as monospaced chunks with no background. During
the field test the owner asked for a search inside the request and to look
at how their earlier macOS client, `app-network-monitor`, shows headers
and JSON.

Evidence read, in `christianalexandre/homebrew-app-network-monitor-client`
(the SwiftUI client; `app-network-monitor` is its iOS SDK):

- `Presentation/Features/LogDetail/Components/HeadersTab.swift`: one
  section per side with "N items", each header the name in 11 pt bold
  monospaced secondary color above the value in 12 pt monospaced,
  selectable, a divider between rows (sorted by name).
- `Components/JsonViewer.swift`: the pretty-printed body in a rounded,
  bordered box on the text background; above it a "Search in JSON..."
  field with "N found" and a clear button; matches drawn in yellow. One
  search per body, not across the request.

The design system has no code text or block (ADR-0134); its colors are
tokens with a light and a dark value (ADR-0145, ADR-0149).

## Options

1. **One search field under the tabs for the whole request**: matches
   highlighted in the shown tab, a count per tab in its label and a total.
   Headers, URL, timing, bodies and cURL all searched.
2. **A search per body, as the reference does.** Misses headers and the
   URL, and a body search would repeat per side.
3. **Leave the search to the toolbar's URL filter.** It filters requests,
   not what is inside one.

## Decision

Option 1, with the reference's header layout and code box.

- `desktop/designsystem/Code.kt`: `CodeText` (monospaced, `SyntaxSpan`s in
  their colors, every case-insensitive match on the highlight color,
  `muted` for a label), `CodeBlock` (the code background, rounded at the
  block's first and last piece so a body chunked over lazy rows reads as
  one box, ADR-0029), `matchesOf`. New tokens: `code`, `highlight`,
  `syntaxKey`, `syntaxString`, `syntaxValue`; `Spacing.none`.
- `desktop/feature/traffic`: headers one per row, name muted above value,
  in the order sent (not sorted: the order is what the device sent), the
  heading with the count; URL, bodies and cURL in a `CodeBlock`; a JSON
  body's keys, strings and literals colored by `jsonSyntax`, per chunk,
  since indented JSON never splits a string over lines.
- The search: `searchedTexts` and `matchCounts` count matches per tab,
  cURL left out as it repeats the URL, headers and body; the field keeps
  its text while another request is picked; "Clear" empties it.

## Consequences

- Finding a header or a field takes a type, and the tab labels say where
  the matches are.
- No jump to the next match (DEBT-0096): a long body is scrolled by hand to the
  highlights.
- Copy is unchanged: headers still `Name: value`, bodies as sent.
