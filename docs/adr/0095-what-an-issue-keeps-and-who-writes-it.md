---
id: ADR-0095
title: What an issue keeps, how long, and who may write it
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0095 — What an issue keeps, how long, and who may write it

## Context

PLAN-0008 M7.1. The roadmap promises issues: "requests + note + tags +
status, also via MCP `save_issue` / `list_issues`" (`docs/plans/README.md`,
M7). An issue is the developer, or an agent, saying "these exchanges show a
problem". Two parts of it are "stop and ask" areas (`AGENTS.md`):

- How long an issue's exchanges stay on disk is security / privacy (what
  is stored). Retention deletes whole sessions, oldest first, past 7 days
  or 1 GiB, and keeps only named runs past both bounds (ADR-0082 B.1,
  C.1). No standing owner decision covers an issue.
- `save_issue` would be the first MCP tool that writes the store. ADR-0005
  says "Tools are read-only at first; tools that change app behavior
  (mocks, replay) require confirmation in the UI", and SDD 10 says
  `desktop/mcp` "never writes" to `desktop/storage`.

Already decided, not reopened here:

- The store keeps traffic as the device sent it (ADR-0081, ADR-0089).
- Who can call the MCP server: loopback, `Host` and `Origin` checks, a
  bearer token, a switch in the settings (ADR-0090).
- A run is a time window across devices; naming one keeps it (ADR-0084,
  ADR-0082 C.1).

## Options

### A. What an issue holds

1. **A title, a free-text note, free-text tags, a status, and one or more
   exchanges from any runs**, linked by id; status is `open` or
   `resolved`. Two states filter the list into "to look at" and "done";
   tags carry everything else ("checkout", "flaky", "backend").
2. **Same, with a third status `wont_fix`** — closer to an issue
   tracker; one more state the list and the MCP schema carry.
3. **Free-text status** — no fixed set to filter on; agents and the
   developer drift ("Open", "open", "todo").

Recommended: 1.

### B. How long an issue's exchanges stay

1. **An issue keeps its linked exchanges past retention**, with their
   bodies and frames and the session, device and app rows they point to,
   the way a named run is kept (ADR-0082 C.1): they count toward the size
   shown in the settings, and when kept data alone passes the size bound
   the window warns instead of deleting. The rest of their session is
   deleted as today. An issue saved last week still shows its evidence.
2. **An issue copies its exchanges** (rows, bodies, frames) into its own
   tables — independent of retention and of later schema changes to the
   traffic tables; every linked exchange is stored twice, and a body cut
   on the device stays cut in the copy too.
3. **Retention ignores issues** — the issue keeps its note and shows its
   exchanges as deleted once their session ages out; nothing stays on
   disk longer than the bounds say, and a week-old issue has no evidence.

Recommended: 1.

### C. What the MCP server may write

1. **`save_issue` creates an issue, or changes the title, note, tags,
   status or exchanges of an existing one, without a confirmation in the
   window**; `list_issues` reads them. No tool deletes an issue or changes
   traffic. An issue records whether it was created by the window or by
   MCP, and the issue list shows it. Saving an issue changes no app
   behaviour (ADR-0005's confirmation is for mocks and replay), and an
   agent working unattended is not blocked on a click. Same access rules
   as every tool (ADR-0090).
2. **`save_issue` creates only**; edits stay in the window — an agent
   that finds a second exchange for the same problem opens a second
   issue.
3. **Every save confirmed in the window** — the developer sees each
   write before it lands; an agent's call waits for a click, or fails
   when nobody is at the desktop.

Recommended: 1.

### D. Deleting

1. **The developer deletes an issue in the window; "Clear all data"
   deletes issues too** (ADR-0082 D: "as if never installed"). A deleted
   issue releases its exchanges to retention, which deletes them on its
   next pass if their session is past the bounds.
2. **"Clear all data" keeps issues and their exchanges** — the button no
   longer means "as if never installed".

Recommended: 1.

## Decision

The owner accepted every recommendation: A.1 (title, note, free-text
tags, `open` or `resolved`, exchanges from any runs), B.1 (linked
exchanges kept past retention like a named run), C.1 (`save_issue`
creates and edits without a confirmation, `list_issues` reads, no tool
deletes) and D.1 (deleted in the window; "Clear all data" deletes issues).

## Consequences

- B.1 adds a second reason to keep a row past retention: M7.2's
  retention pass and its tests name both (named run, issue).
- C.1 makes `desktop/mcp` write to `desktop/storage` through one tool;
  SDD 10's "never writes" and its lifecycle table (a write while the
  store closes, a write lost) change with M7.3.
- A.1's two states can grow later with a new ADR; the MCP schema lists
  them as an enum, so adding one is a schema change agents see.
