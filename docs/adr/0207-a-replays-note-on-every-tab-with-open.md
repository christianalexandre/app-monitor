---
id: ADR-0207
title: A replay's note shows on every tab and opens the call it brought back
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0207 — A replay's note shows on every tab and opens the call it brought back

## Context

PLAN-0018 H8.7. On the owner's first look at the window (2026-11-07),
"Replay does not seem to do anything". ADR-0120 put the note "Sent to the
app; its call shows as a replay" in the Summary tab only
(`RequestDetail.kt`, `summary`). ADR-0204 moved "Replay" into the detail bar,
so it can be pressed from any tab, and on the other tabs nothing showed.
The replayed call joins the list at its end, in arrival order
(`TrafficRepository.observeExchanges`), and is not picked.

The plan asked for the bug to be reproduced first. What was checked:

- `make e2e-ios` (2026-11-07, iPhone 17 Pro simulator): the headless server's
  `ReplayRequest` reached the sample and came back as
  `GET …/json ResponseReceived replay`. The SDK side works.
- "Replay" runs `TrafficViewModel.sendAgain` whatever the tab. The new
  `ReplayTest` case presses it on the Body tab against a real server and a
  stand-in device, and the device gets the `ReplayRequest`.

So the replay was sent. What was missing was feedback outside Summary, and
a way to the call that came back.

Known constraints:

- The desktop does not know the replay's exchange id when it sends:
  `ReplayRequest` carries only the original's `request_id` (SDD 12). The
  replayed call arrives as a new exchange whose `replay_of` is that id, in
  the same session.
- The same request can be replayed several times, so an earlier replay must
  not be taken for the new one.
- The list's rows are the first pages in arrival order. A new call can be
  past them.

## Options

1. **The note in Summary, the call picked when it arrives.** The view jumps
   while the developer reads another tab, and the problem on the other tabs
   stays.
2. **The note at the detail's bottom on every tab, with "Open" once the call
   arrives.** The note uses the same place as the copy note (ADR-0153).
   Before sending, the view model reads the ids of the request's earlier
   replays. After a `SENT`, it watches the same query until a replay of the
   request comes that is not among them. The query reads the picked
   session's calls whose URL contains the original's (the indexed `url LIKE`
   of ADR-0177), up to 10 000 rows. "Open" picks that call by id, as "Show
   original" does (ADR-0120), so a call past the loaded pages opens too.
3. **A new `ReplayAccepted` message with the new request id.** This would
   change the protocol and every SDK for something the desktop can already
   find.

## Decision

Option 2. The note is a `Toast` with an optional action, in
`desktop/designsystem` and its gallery. It stays until another request is
picked, and it is not timed like "Copied". Its text becomes "The app sent it
again" once the call is found. The first replay found after the send is the
one kept; later ones do not replace it. A refusal still wins over both.

## Consequences

- Every tab tells what a Replay did, and the developer reaches the call
  without scrolling the list.
- One more query per replay sent from the window, the picked session's
  calls to one URL, read again on each change of the list until the
  replay comes back. It stops at the first one found or when another
  request is picked.
- A session with over 10 000 calls to one URL may not find the replay.
  The note then stays "Sent to the app…", as before this ADR, and the row
  is still in the list.
- Like "Show original", "Open" clears the list's filter.
