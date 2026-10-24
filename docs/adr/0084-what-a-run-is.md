---
id: ADR-0084
title: What a run is for the developer, and what the window shows by default
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0084 — What a run is for the developer, and what the window shows by default

## Context

PLAN-0006 M5.1 (d). The roadmap promises "named runs" (README, M5) and, in
M7, run-to-run diffs and per-endpoint benchmarks; M6 exposes runs to MCP
agents. What a run means is product scope (`AGENTS.md`). Today the window
shows devices and, per device, everything since the app started (SDD 01).

Already decided, not reopened here:

- A session is one WebSocket connection from `Hello` to close
  (ADR-0009); an app restart or a reconnect opens a new one.
- Retention keeps named runs past its bounds if ADR-0082 C.1 is accepted.

## Options

### A. What groups traffic into a run

1. **A run is a time window on the desktop, across devices.** One run is
   always open; every session that connects while it is open belongs to
   it. The developer starts a new run by hand ("New run", one click or a
   shortcut), which closes the current one. At first launch, and when the
   app starts after a quit, a new unnamed run opens. Fits "test the
   checkout flow on the Pixel and the iPhone, then compare with
   yesterday's run".
2. **A run is one session** — automatic, no click; a reconnect (backoff,
   Wi-Fi blip) splits one test into several runs, and two devices tested
   together are two runs.
3. **A run per device**, opened by hand — groups by device, which the
   device list already does.

Recommended: 1.

### B. Naming

1. **Unnamed by default**, shown as its start time ("Oct 24, 14:03");
   named at any time, while open or after, by editing the title. A name
   is what keeps a run past retention (ADR-0082 C).
2. **Named when started** — a dialog on every "New run"; the common case
   (a quick look) pays for the rare one.

Recommended: 1.

### C. What the window shows

1. **The current run by default**: the device list and request list are
   filtered to it, a run picker above the device list switches to an
   earlier run or "All runs". After a restart, the earlier traffic is one
   click away in the picker, not mixed with today's.
2. **Everything by default**, runs as a filter — after a week the list
   opens on thousands of old rows.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- The schema (M5.2) has a `runs` table and a session belongs to exactly
  one run; M7 diffs compare two runs.
- M5.6 tests run grouping and shows a screenshot of the picker with two
  named runs.
- A.1 means a session spanning "New run" stays in the run it started in.
