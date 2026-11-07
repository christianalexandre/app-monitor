---
id: ADR-0214
title: A hidden host is listed, not the hosts shown
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0214 — A hidden host is listed, not the hosts shown

## Context

PLAN-0018 H8.R1, from the H8 milestone review's finding 1. The sidebar's
HOSTS hid a host by picking every other one in `ExchangeFilter.hosts`
(ADR-0148's "the hosts picked, none keeping all", kept by ADR-0205 and
ADR-0209). Read in `TrafficScreen.kt`'s `withHost` and in `Search.kt`'s
`exchanges.host IN (...)` and `liveMatcher`: with `a.example` and
`b.example` listed, hiding `b.example` stored `hosts = {a.example}`. A
call to `c.example` after that, and every row without a host (a URL that
does not parse, a WebSocket frame before its request), were dropped from
the list while the sidebar showed `c.example` with its check. Hiding a
noisy host and then using the app hits it.

## Options

1. **List the hidden hosts** — a new `hiddenHosts` field in the domain's
   and the store's `ExchangeFilter`, read as
   `(exchanges.host IS NULL OR exchanges.host NOT IN (...))` and its twin
   in the live matcher. A host seen later shows, a row without a host
   stays. `hosts` stays for any caller that picks hosts.
2. **Refresh the picked hosts as new hosts arrive** — the view model would
   add each new host to `hosts`. It races the list's query, and rows
   without a host stay dropped.

## Decision

Option 1. The sidebar writes `hiddenHosts` only: hiding a host adds it,
showing it removes it, an empty list shows all. Hiding the last host shown
and the label's button keep ADR-0209's `noHost`, which hides every host,
later ones too. Showing one host while every host is hidden lists the
others as hidden. A filter that picked hosts becomes the list of the
others at the first toggle.

## Consequences

- `FilterMatchTest` runs `hiddenHosts` on the store and the live list, a
  row without a host included.
- The filter is not stored across launches, so nothing migrates.
- ADR-0148's picked `hosts` is no longer written by the window; it stays
  in the filter's API.
