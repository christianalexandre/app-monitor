---
id: DEBT-0104
title: The live list repeats the store's decoding and filter match
status: Open
date: 2026-10-30
trigger: "A new `network` event type, a new `ExchangeFilter` field, or a change to how `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt` decodes an event or `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Search.kt` matches a filter"
paid_date:
---

# DEBT-0104 — The live list repeats the store's decoding and filter match

## What is missing

`LiveTraffic.network` decodes the four `network` events as `Ingest` does
(status 0 as no status, the last `grpc-status`, an event for an unknown
request creating it). `LiveTraffic`'s search matches an `ExchangeFilter`
in Kotlin as `SearchQuery` matches it in SQL. ADR-0141 accepted this:
"`LiveTraffic`'s filter match must follow `SearchQuery`'s". A change on
one side is copied by hand. Symptom: a request listed live but not once
saved (or the reverse), or a filter that matches differently in Live and
in a stored run.

## Why it was accepted

The live list holds rows in memory and the store in SQLite (ADR-0140,
ADR-0141). One matcher for both needs either SQL over memory or the
store's filter evaluated in Kotlin, and neither was worth it for the
first live list. `LiveTrafficTest` holds the decoding and filter cases,
and `SearchTest` the store's.

## Trigger

A new `network` event type, a new `ExchangeFilter` field, or a change to
how `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`
decodes an event or
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Search.kt`
matches a filter. Then the decoding moves to one function both call, and
the filter cases become one table run against both.

## How it was paid

> Filled when closing.
