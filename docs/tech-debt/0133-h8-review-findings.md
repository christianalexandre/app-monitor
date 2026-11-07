---
id: DEBT-0133
title: Findings of the H8 milestone review that normal use does not hit
status: Open # Open | Paid | Dropped
date: 2026-11-07
trigger: "The next PR that touches a file its items name: `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/BodyFormat.kt`, `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/DetailTabs.kt`; for items 1 to 4, a PR that changes `sendAgain`, `replaysOf` or the list's `more` in `TrafficViewModel.kt`"
paid_date:
---

# DEBT-0133 — Findings of the H8 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0018 (`c293236..7eafe78`) found these, each read in the code
here (PLAN-0018, *H8 milestone review*); none was run end to end:

1. `TrafficViewModel.kt` `sendAgain`: the replay is sent from a coroutine after a read of the
   replays already there; a pick changed before that read returns sends nothing and says
   nothing, and two clicks inside it send twice.
2. `TrafficViewModel.kt` `sendAgain`: the arrival is awaited in `runs.value.shownRunId`'s list,
   so a Replay from a stopped recording is sent, but the note never turns to "The app sent it
   again" with "Open", and the query stays subscribed until the next pick.
3. `TrafficViewModel.kt` `replaysOf`: while a replay is awaited, up to `REPLAY_SEARCH_LIMIT`
   (10,000) exchanges are read and mapped at each store change to find one `replayOf`.
4. `TrafficViewModel.kt`: the list header's `more` reads `limit.value` outside the combine's
   inputs, and says `200+` when the store holds exactly one page.
5. `BodyFormat.kt` `defaultFormat`: every UTF-8 body is run through the JSON parser when it
   opens, up to 16 MiB of HTML or text, which the `Content-Type` gate used to skip.
6. `BodyFormat.kt` `indentJson`: a string token is copied whole and the `MAX_RENDERED_CHARS`
   bound checked between tokens, so a multi-megabyte object key is drawn past the bound.
7. `DetailTabs.kt` `contentTypeOf`: takes the last `Content-Type` line where the decoding takes
   the first, so two lines name a type the body was not decoded by.

## Why it was accepted

None is hit in normal use, per ADR-0094's rule: 1 needs a pick inside one store read; 2 needs
a Replay from a stopped recording and only loses the note's second state, the call is sent;
3 and 5 cost time, none measured; 4 is one wrong `+`; 6 needs a crafted key; 7 needs a header
repeated.

## Trigger

The next PR that touches `BodyFormat.kt` or `DetailTabs.kt` in
`desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/`, or that changes
`sendAgain`, `replaysOf` or `more` in its `TrafficViewModel.kt`.

## How it was paid

> Filled when closing.
