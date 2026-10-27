---
id: ADR-0087
title: Exchange search is one WHERE built from the filters that are set
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0087 — Exchange search is one WHERE built from the filters that are set

## Context

PLAN-0006 M5.7 asks for search and filters over the stored exchanges — text
in URL and host, method, status class, failed only, device and app, run —
"answered by indexed queries, not by filtering in memory". SDD 09 already
indexes `exchanges` on (`session_ref`, `id`), `host`, `status_code` and
`method`, and `sessions` on `run_id`. Pages are by key (`id > :afterId`).

SQLDelight 2.4.1 generates one prepared statement per labelled query in a
`.sq` file. Eight optional filters written there are either 256 queries or
one with `(:x IS NULL OR column = :x)` per filter; SQLite plans a statement
when it is prepared, before the values are bound, so that form cannot pick
the index of the filter actually set.

Evidence, `EXPLAIN QUERY PLAN` of the built statement on a test store
(`SearchTest`, sqlite-jdbc as pinned by ADR-0085), 2026-10-24:

- `method = ?` → `SEARCH exchanges USING INDEX exchanges_method (method=? AND rowid>?)`,
  already in `id` order.
- status class → `SEARCH exchanges USING INDEX exchanges_status_code (status_code>? AND status_code<?)`,
  then a sort of what matched.
- session, run, device, app → `SEARCH exchanges USING INDEX exchanges_session_ref_id (session_ref=? AND id>?)`
  over the ids of a subquery on `sessions` (`sessions_run_id` for the run;
  the device and app subqueries scan `sessions`, one row per connection).
- text (`url LIKE '%…%'`) and failed only → `SEARCH exchanges USING INTEGER PRIMARY KEY (rowid>?)`:
  a walk in `id` order that stops at the page's `LIMIT`. No B-tree index
  answers a substring; over 100 000 exchanges and no match, the walk took
  6 ms for text and 4 ms for failed only on the owner's MacBook Pro.

## Options

1. **Static `.sq` query, `(:x IS NULL OR …)` per filter.** Typed and
   observable for free; one plan for every combination, which cannot use
   the index of the filter that is set.
2. **One `WHERE` built per filter set, in a `Query` subclass.** Only the
   clauses of the filters set are written, values bound in order, so SQLite
   plans each combination with its index; the class registers listeners on
   `exchanges` and `sessions` like the generated ones, so the window still
   follows commits. Costs a hand-written mapper and SQL outside the `.sq`
   files' compile-time check.
3. **FTS5 table for the text, option 2 for the rest.** Indexes substrings
   (trigram tokenizer); needs a migration, a second write per exchange and
   triggers, for a walk measured at 6 ms per 100 000 rows.

## Decision

Option 2: `SearchQuery` in `desktop/storage` builds the `WHERE` from the
`ExchangeFilter` fields that are set, ordered by `id` with a `LIMIT`; text
is a case-insensitive `LIKE` on `url`, which holds the host, with `%`, `_`
and `\` escaped. FTS5 waits for DEBT-0063.

## Consequences

- Each indexed filter keeps its index whatever else is set;
  `SearchTest.indexedFiltersUseTheirIndex` fails if a plan stops using it.
- The SQL is checked by `SearchTest`, not by the SQLDelight compiler: a
  schema change to the columns it reads must change it too.
- The window's device and app filter is the session picked in the device
  list; `ExchangeFilter` carries device, app and run for other readers (the
  MCP server, M6).
- Text and failed only walk the rows in `id` order: DEBT-0063.
