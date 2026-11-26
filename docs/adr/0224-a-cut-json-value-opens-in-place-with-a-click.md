---
id: ADR-0224
title: A cut JSON value opens in place with a click, as part of the body's rendering
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-25
superseded_by:
---

# ADR-0224 — A cut JSON value opens in place with a click, as part of the body's rendering

## Context

PLAN-0020 H9.4. ADR-0208 cuts a JSON string value over 200 characters to
`… (N more)` in the JSON view. The owner (2026-11-15): clicking that value
shows it whole, wrapped, and clicking again folds it. The search counts and
walks an opened value as shown text; Copy, Text and Hex do not change.

Known constraints:

- A body is rendered off the UI thread into chunks (ADR-0029), and the
  search runs over those chunks (ADR-0152, ADR-0153).
- The Body tab sits in a `SelectionContainer`, so text stays selectable.
- Compose Multiplatform 1.12.2 (`gradle/libs.versions.toml`) ships
  `LinkAnnotation.Clickable` in `ui-text-desktop-1.12.2.jar`: a clickable
  range inside one `Text`, with its own hover style.

## Options

1. **Opened values are an input of the rendering.** The indent pass takes
   the set of opened values (by their order in the cut list) and writes
   those whole. The rendering records where each cut value sits in the
   view's text, and per chunk where it falls, so the chunk's `CodeText`
   makes that range a `LinkAnnotation.Clickable`. The search needs nothing
   new: the chunks hold what is shown, and `hiddenMatches` skips opened
   values. A click indents the body again, off the UI thread, as a format
   switch already does; the old rendering stays on screen until then.
2. **Splice the opened value into the shown chunk on the UI thread.** No
   new pass, but the chunks, the search's positions and the jump rows would
   have to be patched in place, and an opened value can pass a chunk's
   character bound (ADR-0029).
3. **Show an opened value in a dialog.** One more surface, and the search
   could not walk it as part of the body.

## Decision

Option 1. `CodeText` takes `links` (`CodeLink`: a range and a click,
underlined under the pointer). Opened values belong to one body side and its
chosen format: switching format or picking another request folds them all.

## Consequences

- A cut value opens and folds where it is, its matches counted and walked
  like the rest of the view; "· N in cut values" counts only the values
  still cut.
- Opening a value in a 16 MiB body costs one indent pass, as switching to
  JSON does (ADR-0029).
- An opened value wraps into many rows, so a match below it in the same
  chunk lands lower after "↓" (DEBT-0131).
- The click needs a pointer: no keyboard focus reaches a link inside the
  code text.
