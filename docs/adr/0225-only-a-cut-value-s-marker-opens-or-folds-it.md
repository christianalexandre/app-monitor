---
id: ADR-0225
title: Only a cut value's marker opens it, and a marker before an opened value folds it
status: Accepted
date: 2026-11-25
superseded_by:
---

# ADR-0225 — Only a cut value's marker opens it, and a marker before an opened value folds it

## Context

PLAN-0020 H9.R3, finding 6 of the H9 milestone review. ADR-0224 made the whole cut value a
link, from its opening quote to its closing one, opened or not. The Body tab sits in a
`SelectionContainer`, so a click inside an opened value to select part of it (a double click on
a word) folded it instead.

## Options

1. **The whole value stays the link.** Selection inside an opened value keeps folding it.
2. **Only `… (N more)` opens; an opened value gets `(fold)` after its closing quote.** Text in
   the value selects as any other text. A 5 KB value puts `(fold)` many rows below the click
   that opened it, so folding means scrolling to the end of the value.
3. **Only `… (N more)` opens; an opened value gets `(fold)` before its opening quote**, as
   `"key": (fold) "value"`. The fold sits where the value starts, next to the click that opened
   it. `(fold)` is not JSON, like `… (N more)`, which the view already adds (ADR-0208).

## Decision

Option 3. `CutValue.start` and `end` mark the marker, not the value. Copy, Text, Hex and cURL
keep the bytes as sent, as before. The search finds `(fold)` like `… (N more)`: both are the
view's text (ADR-0208).

## Consequences

- A click or a double click inside an opened value selects, as anywhere else in the view.
- Folding takes a click on `(fold)`, at the value's start; opening takes one on `… (N more)`.
- Searching for "fold" also counts each `(fold)` shown.
