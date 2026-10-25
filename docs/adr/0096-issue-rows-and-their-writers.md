---
id: ADR-0096
title: Issue rows, their writers' order, and what retention trims
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0096 — Issue rows, their writers' order, and what retention trims

## Context

PLAN-0008 M7.2 stores ADR-0095's issues. Three writers touch an issue or
the exchanges it links: the window (create, edit, delete), the MCP server
from M7.3 (`save_issue` creates and edits), and the retention pass and
"Clear all data", which delete exchanges. Every write already goes through
the one writer thread, retention between batches (SDD 09, ADR-0088). What
is open is how an issue is laid out, what an edit from one writer does to
an edit from the other, and how retention keeps a linked exchange but not
the rest of its session (ADR-0095 B.1).

## Options

### A. Layout

1. **`issues`, `issue_tags` (issue, tag) and `issue_exchanges` (issue,
   exchange) tables**, a migration from version 1. A tag filter is an
   indexed lookup; a link is a foreign key, so an exchange that is gone
   cannot be linked.
2. **Tags and exchange ids as text in `issues`** — one table, but a tag
   filter is a `LIKE` on every row and nothing stops a link to a deleted
   exchange.

### B. Two writers editing one issue

1. **An edit carries only the fields it changes, applied on the writer in
   queue order**: the window sends what the developer changed since the
   issue opened, so an MCP edit of another field in between is kept; the
   same field goes to the later write. The exchange ids are checked in
   the batch's transaction, so a write naming an exchange retention just
   deleted writes nothing and says which ids, instead of failing the
   batch on the foreign key.
2. **Whole-issue writes** — the later writer silently undoes the earlier
   one's other fields.
3. **A version per issue, a stale write refused** — safe, but an agent
   working unattended has to read and retry, and the window needs a
   conflict screen.

### C. Retention of a session with a linked exchange

1. **The session is a candidate while it holds anything else**: the pass
   deletes its unlinked exchanges and raw envelopes and keeps the row, its
   device and app; once only linked exchanges are left it is no longer
   listed, so a pass ends. A deleted issue makes the session an ordinary
   candidate again.
2. **Keep the whole session** — simpler, but one linked request keeps a
   long session's every body past both bounds.

## Decision

A.1, B.1 and C.1. `Storage.createIssue`, `updateIssue` (an `IssueChange`
with null for "unchanged") and `deleteIssue` queue on the writer; the first
two return an `IssueWrite` once the batch is committed or lost.

## Consequences

- M7.3's `save_issue` maps straight onto `updateIssue`: an omitted field is
  null, unknown ids are `IssueWrite.UnknownExchanges`, a closing store is
  `NotSaved`.
- An issue's kept exchanges count toward the size bound; when they alone
  pass it, `keptOverBound` warns as for named runs (ADR-0082 C.1).
- Adding a third status is a migration (the `CHECK`), per ADR-0095.
