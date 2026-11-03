---
id: ADR-0177
title: Text search and "failed only" read compact indexes
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0177 — Text search and "failed only" read compact indexes

## Context

ADR-0087 left text (`url LIKE '%…%'`) and "failed only" as a walk of
`exchanges`, on a measure of 6 ms over 100 000 rows. DEBT-0063 proposed an
FTS5 trigram table and a partial index once the walk passed 100 ms; PLAN-0016
H6.2 asks for the index now.

That 6 ms measured rows with one header. With 30 headers each way (a 620 MB
file, near 6 KB per exchange), `SearchTest` on the owner's MacBook Pro,
2026-11-03, no match over 100 000 exchanges:

| Read | Walk | Index |
| --- | --- | --- |
| Through the store, right after the writes | text 1.57 s, failed 1.52 s | text 18 to 48 ms, failed under 1 ms |
| Plain connection after a clean close, first read | text 399 ms, failed 35 ms | text 9 ms, failed under 1 ms |
| Same, read again | text 39 to 44 ms, failed 34 to 54 ms | text 8 ms, failed under 1 ms |

So the walk is slow while a recording is being written and on the first read
of a file, not always.

`EXPLAIN QUERY PLAN` on a plain connection with the indexes present and no
hint, first reads: text alone still takes the primary key (1.15 s), and a run
with "failed only" takes the session index (2.31 s). The store runs no `ANALYZE`, so the planner has no statistics.

## Options

1. **FTS5 trigram table on `url`, partial index on `failed`** (DEBT-0063's
   proposal) — sublinear for a rare match. Checked on SQLite 3.51 (Python's): a
   `LIKE` under three characters scans the table anyway (`INDEX 0:L0`), and
   `MATCH` folds non-ASCII case where `LIKE` and `LiveTraffic` do not, so only
   `LIKE` keeps today's meaning. A table and triggers to keep in step, for more
   than the measures ask.
2. **`url` in compact B-tree indexes, partial index on `failed`, the search
   naming them** — still linear, over about 100 bytes per row instead of 6 KB.
   SQLite tests `LIKE` on the index and reaches the row only for a match. The
   same `LIKE`, so text means what it meant.
3. **Leave the walk, drop DEBT-0063** — a search during a recording stays at
   1.5 s at this row size.

## Decision

Option 2. Migration 9 replaces `exchanges_session_ref_id` with
`exchanges_session_ref_id_url` (`session_ref`, `id`, `url`), and adds
`exchanges_id_url` (`id`, `url`) and `exchanges_failed` (`id`, `session_ref`)
`WHERE failed = 1`. `SearchQuery` writes `INDEXED BY`: `exchanges_failed` for
"failed only"; for text, the session one under a session, run, device or app
filter, else `exchanges_id_url`.

## Consequences

- 100 000 exchanges at this size: the file grows 3.6% (624 to 647 MB), and
  writing their 200 000 envelopes takes 8% longer (4.95 to 5.38 s, four rounds).
- The first open of a file after the update builds the three indexes: 300 ms
  for this 100 000 exchange file, on a warm disk cache.
- Text under a method or host filter now walks `url` first, then reads the
  matches. A filter whose text matches nearly every row costs what the method's
  index cost.
- Text stays linear: a store of millions of small rows pays for each. FTS5 stays
  available if that case appears; nothing here blocks it.
- `SearchTest` keeps real headers on its rows and bounds the time at 500 ms;
  `indexedFiltersUseTheirIndex` guards the plans, without a clock.
- Supersedes only ADR-0087's text and "failed only" evidence and walk; its
  `WHERE` built from the fields set stands.
