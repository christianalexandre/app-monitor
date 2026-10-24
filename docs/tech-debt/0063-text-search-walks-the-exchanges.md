---
id: DEBT-0063
title: Text search and "failed only" walk the exchanges instead of an index
status: Open
date: 2026-10-24
trigger: SearchTest's no-match text search over 100 000 exchanges passes 100 ms, or ADR-0082's size bound is raised past 1 GiB, or the MCP server (M6) exposes text search
paid_date:
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
prints the no-match time; when it passes 100 ms, when ADR-0082's size bound
is raised past 1 GiB, or when the MCP server (M6) lets agents search text
over every run — an FTS5 trigram table on `url` and a partial index on
`failed`, in one migration.

## How it was paid

> Filled when closing.
