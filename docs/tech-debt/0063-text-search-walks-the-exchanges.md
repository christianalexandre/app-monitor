---
id: DEBT-0063
title: Text search and "failed only" walk the exchanges instead of an index
status: Paid
date: 2026-10-24
trigger: SearchTest's no-match text search over 100 000 exchanges passes 100 ms, or ADR-0082's size bound is raised past 1 GiB
paid_date: 2026-11-03
---

# DEBT-0063 — Text search and "failed only" walk the exchanges instead of an index

## What is missing

`SearchQuery` (ADR-0087) answers the text filter with `url LIKE '%…%'` and
"failed only" with `failed = 1`, which no index serves: SQLite walks the
exchanges in `id` order until a page is full. A rare or absent match reads
every row the other filters leave. Symptom: typing in the search field of a
very large history lags.

## Why it was accepted

Measured in `SearchTest.unindexedFiltersOverManyExchangesInBoundedTime`:
100 000 exchanges, no match, 6 ms for text and 4 ms for failed only. FTS5 for
the text and a partial index for `failed` both need a schema migration and
cost a write per exchange, for a delay nobody can see at today's sizes.
Retention (ADR-0082, 1 GiB) bounds how many rows there are.

## Trigger

`desktop/storage/src/test/kotlin/appmonitor/desktop/storage/SearchTest.kt`
prints the no-match time; when it passes 100 ms, or when ADR-0082's size
bound is raised past 1 GiB — an FTS5 trigram table on `url` and a partial
index on `failed`, in one migration.

Re-triggered at M6.4 (`search_requests`, PLAN-0007): the third trigger, "the
MCP server exposes text search", fired. It is dropped: an agent sends one
search per step, not one per keystroke, and the walk it pays is the one
`SearchTest` measures (6 ms over 100 000 exchanges), small beside the HTTP
round trip of the call; retention still bounds the rows. The two measured
triggers stay.

## How it was paid

Paid by PLAN-0016 H6.2b (`refactor/search-live-one-decoding`), by ADR-0177:
`url` in compact indexes and a partial index on `failed`, not FTS5. Over 100 000
exchanges with 30 headers each way, no match, through the store: text 1.57 s to
48 ms at most, failed only 1.52 s to under 1 ms.

The 6 ms this item accepted was measured on rows with one header: the trigger's
number never fired because the test's rows were too small. A timing test says
nothing unless its rows look like the real ones.
