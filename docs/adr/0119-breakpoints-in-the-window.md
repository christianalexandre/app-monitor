---
id: ADR-0119
title: A held call opens on its own; breakpoints get their own panel
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0119 — A held call opens on its own; breakpoints get their own panel

## Context

ADR-0111 put breakpoints in the window only (E.1) and decided what a hold
does: the call waits on the device until a resume, edited or not, an
abort, its timeout (60 s by default) or the end of its session. ADR-0118
put the held calls in `HeldCalls` and the diff in `resumeOf`, so the
window edits `HeldCall.unedited` and hands it back. PLAN-0010 M9.8 left
how the window shows a rule and a held call to this task.

Already decided, not reopened here:

- The Mocks panel's shape (ADR-0114 A.1): a button under the run picker,
  a list of every app's rules in match order, a form opened from the list.
- A resume carries only what changed; a `██` header left alone keeps the
  device's value (ADR-0111 C.1).
- A rule's timeout is 1 ms to 10 minutes (ADR-0118 E.1).

Evidence, 2026-10-25:

- `HeldCalls.held` is a `StateFlow`, oldest first; a call leaves it on a
  resume, an abort, its timeout or its session's end (`HeldCalls.kt`,
  `HeldCallsTest`).
- The approval dialog already opens on its own for the oldest pending
  device and counts the others ("N more waiting", `App.kt`).
- `BreakpointsUiTest`: a call held by a stand-in device is shown, resumed
  edited with only the URL and body sent back, and another aborted.

## Options

### A. How a held call reaches the developer

1. **A dialog opens on its own for the oldest held call, "N more held"
   under it, as the approval dialog does.** A hold has a deadline, often
   a minute: it is seen at once. It closes by itself when the call is
   resumed, aborted, timed out or its device goes. The Breakpoints button
   counts the held calls.
2. **A count on the button and a list in the panel.** The window stays
   free while a call waits, but a call easily times out unseen.

### B. What the held call's form edits

1. **The held side as text: method and URL at the request stage, the
   status at the response stage, headers one `Name: value` per line, the
   body as UTF-8 text, or a binary body kept or cleared**, as the mock
   editor does. A body the device cut says so: left alone it goes on
   whole, edited it is replaced by what is shown. One "Resume" sends
   `resumeOf` of the form, so an untouched form resumes unchanged; "Abort"
   sends an editable message, "Aborted from App Monitor" by default.
2. **A field per header.** Adding and removing a header takes buttons;
   the mock editor's text form is already known to the developer.

### C. A rule's timeout in the form

1. **Whole seconds, 1 to 600, 60 by default.** Within ADR-0118 E.1's
   bounds; below a second is a test's need, not a developer's.
2. **Milliseconds,** as the wire has them.

## Decision

A.1, B.1, C.1. The Breakpoints panel sits beside "Mocks" and mirrors its
list and form, without pending state (ADR-0111 E.1).

## Consequences

- The held call's dialog covers the window while a call waits; looking at
  other traffic means resuming or aborting first, or letting it time out.
- A header whose value has spaces at its ends comes back trimmed, so it
  is sent as changed.
- The panel cannot reorder breakpoints, as mocks cannot (DEBT-0082).
