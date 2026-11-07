---
id: DEBT-0131
title: A match jumped to in wrapped JSON lands lower than its line
status: Open # Open | Paid | Dropped
date: 2026-11-07
trigger: The owner reports a Body tab match out of view after "↓", or a PR changes the jump in `RequestDetail.kt`
paid_date:
---

# DEBT-0131 — A match jumped to in wrapped JSON lands lower than its line

## What is missing

"↑" and "↓" on the Body tab scroll to a match's chunk with an offset of its
line number times the code line height (ADR-0153). Since ADR-0208 the JSON
view wraps its lines, so every wrapped line above the match in the same chunk
adds a row the offset does not count: the match shows lower than three lines
under the top, and with many wrapped lines it can fall under the pane.

## Why it was accepted

String values over 200 characters are cut in the JSON view, so a wrapped line
is at most a few rows and most lines do not wrap. Measuring would need each
chunk's `TextLayoutResult` before scrolling to it, which a `LazyColumn` does
not have for items not yet laid out.

## Trigger

The owner reports a Body tab match out of view after "↓", or a PR changes the
jump in `RequestDetail.kt` (`jumpTo`, `bodyRowOf`).

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
