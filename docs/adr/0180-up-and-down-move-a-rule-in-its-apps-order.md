---
id: ADR-0180
title: "\"Up\" and \"Down\" move a rule in its app's order"
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0180 — "Up" and "Down" move a rule in its app's order

## Context

The first active rule that matches a call answers it (ADR-0110 B.1), and the first enabled
breakpoint holds it (ADR-0119). A rule goes last when made (ADR-0113), so a new, narrower rule
never answers under an older, wider one unless the wider one is deleted and made again
(DEBT-0082). PLAN-0016 H6.2 asks for a rule, and a breakpoint with it, to move. No tool edits a
rule (ADR-0114 C.1), which this keeps.

Evidence, 2026-11-03:

- The panels list every app's rules, by app id then `position` (`Mock.sq`'s `all`,
  `Breakpoint.sq`'s `all`).
- `position` is `max + 1` when made, and a delete leaves a gap (`insert`, `delete`).
- `observeActiveMocks` and `observeActiveBreakpoints` read `position` and emit only on a change
  (`Storage.kt`), so the push needs no new code: `MockPushTest` sees the new order.
- Every store write goes through the write queue, in one batch's transaction (SDD 09).

## Options

### A. How the window moves a rule

1. **"Up" and "Down" on each row, each disabled at the end of its app's rules.** One click,
   with `TextButton` as it is, and a test can drive it.
2. **Dragging a row.** The design system has no drag in a list, and a list of every app's rules
   would have to refuse a drop on another app's.

### B. What a move writes

1. **Swap `position` with the neighbour in the same app, on the write queue.** Two rows change;
   gaps stay, harmless; other apps cannot be touched. Both rows' `updated_at_ms` move, as the
   `updated_at` of `create_mock`'s answer is the row's last write.
2. **Renumber the app's rules.** Every row of the app is written for one click, for no reader
   that needs consecutive numbers.

### C. Which rules a move steps over

1. **Its neighbour in the list, off and pending ones included.** The list is the order, so one
   click moves one row. Stepping over an off rule changes nothing a device is sent yet, and it
   keeps that place when turned on.
2. **The next active one.** One click would move a rule past rows the developer did not ask
   about, and what "active" means differs between mocks and breakpoints.

## Decision

A.1, B.1, C.1. A move answers nothing, as a delete does: the list shows the result, and a move
at an end, or of a rule just deleted, writes nothing. No migration: only queries change.

## Consequences

- A rule's place can be chosen, which also moves the cut past one frame (ADR-0113 C.1): a rule
  past the frame is sent once moved ahead of the rules that fill it.
- Moving a rule a long way takes one click per row; dragging can come when a list that long exists.
- An MCP rule stays pending while moved; its confirmation is still the window's (ADR-0110 F.1).
