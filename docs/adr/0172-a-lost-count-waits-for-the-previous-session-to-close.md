---
id: ADR-0172
title: A session's lost count waits for its previous session to close
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0172 — A session's lost count waits for its previous session to close

## Context

ADR-0166 counts as lost the frames a device wrote on its previous session
that the desktop never read, and looked that session up among the closed
ones when the new session's first `DeliveryReport` arrived. The H5 milestone
review (PLAN-0015) found the common case of a loss missing it: after a Wi-Fi
blip the device is back within its backoff, while the desktop holds the old
socket until a ping goes unanswered (`pingPeriod` of 15 s, then its timeout).
The old session was not closed yet, so `lost` became null for good, and that
null carried into every later session of the SDK's start. A device that lost
frames showed nothing, or "lost unknown". `AppMonitorServerTest` shows it: the
new test fails when a count pending at the report is never settled.

## Options

1. **The count is pending until the previous session closes.** A ledger keeps
   every session, open ones too; a report naming an open session leaves `lost`
   null and links the two, and that session's close settles the count, down a
   chain of reconnects. `DeviceSession.drops` changes once more.
2. **Close the old socket when the new session names it.** The answer is
   immediate, but frames that reached the desktop and wait in `incoming`
   behind a lagging collector would count as lost, or be thrown away.
3. **Wait for the old session inside the report's handling.** It stops the new
   session being read for up to about 30 s, which ADR-0167 exists to avoid.

## Decision

Option 1, in `LostLedger`. `lost` null now also means "not known yet". Closed
sessions are evicted past the 1024 latest, as before; open ones never are, as
their next session still reads them. `TrafficStore` also stores a session's
drops when a lost count settles, as an idle app sends no envelope to carry it;
the headless server prints a changed count with the next envelope or at the
disconnect.

## Consequences

- For up to about 30 s after a reconnect, a device with other drops shows
  "lost unknown" before its number.
- A session that closes before its previous one keeps a null `lost` in the
  store, since its collector has ended. The ledger still passes the count on
  to the next session, which is the one the devices pane reads then.
- ADR-0166's other limits stay: a lower bound on a mid-frame death, nothing
  for the last session of an SDK start.
