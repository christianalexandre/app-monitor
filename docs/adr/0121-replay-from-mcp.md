---
id: ADR-0121
title: A replay an agent asks waits in memory for a click, and the tool answers at once
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0121 — A replay an agent asks waits in memory for a click, and the tool answers at once

## Context

ADR-0111 E.1 lets an MCP client ask a replay "under ADR-0005's
confirmation": nothing an agent asks reaches a device before the
developer clicks. ADR-0120 decided the wire (`ReplayRequest` by request
id), that a replay is sent only to the exchange's own live session
(C.1), and split the MCP half into PLAN-0010 M9.13 (D.1): where a
replay waiting for that click lives, how the window shows it, the tool's
shape, and how MCP rows show a replay.

Already decided, not reopened here:

- `create_mock` leaves its rule pending in the store until "Confirm" in
  the Mocks panel (ADR-0114 C.1): a rule outlives sessions and restarts.
- A device waiting for approval and a held call each open a dialog on
  their own, the oldest first (ADR-0027, ADR-0119 A.1).
- `desktop/mcp` reads `desktop/storage` and does not know `desktop/app`
  (ADR-0092, SDD 10).

Evidence, 2026-10-25:

- `Replays.send` refuses an ended session and a device without
  `interaction` (`desktop/app/.../store/Replays.kt`, ADR-0120 C.1): a
  replay asked of an ended session can never be sent.
- The MCP server is stateless, one JSON response per `POST`, and has no
  server-to-client notifications (SDD 10, ADR-0092): a tool can answer
  only once, inside the client's request.
- MCP clients "SHOULD establish timeouts for all sent requests" and
  "SHOULD always enforce a maximum timeout" (MCP specification
  2025-06-18, *Lifecycle*, *Timeouts*): a call held until a person
  clicks could outlast it.
- `exchanges.idOf(session_ref, request_id)` reads one row by the unique
  index (`Exchange.sq`); `get_request` already resolves `replayOfId` with
  it (`Storage.exchange`).

## Options

### A. Where a replay waiting for confirmation lives

1. **In memory, in `Replays`, until confirmed, refused or its session
   ends.** It can only be sent to its live session, so it dies with it;
   no migration.
2. **A table in the store.** It would outlive the session it can no
   longer reach, and a restart, for nothing it could do.

### B. How the window shows it

1. **A dialog for the oldest waiting replay, opened on its own**, as an
   approval and a held call: the method and URL, the device and app, "An
   agent asks to send this request again", the count of others waiting,
   and "Refuse" / "Send again" (with ADR-0111's warning for a method other
   than `GET`, `HEAD`, `OPTIONS`). "Send again" goes through
   `Replays.send`, as the detail's button; either button closes it. A
   session that ends drops its waiting replays, so the dialog never
   offers one that cannot be sent.
2. **A list in a panel with a counter**, as the Mocks panel. One more
   panel for something that waits seconds, not days.

### C. The tool

1. **`replay_request(id)` answers at once, `status: "waiting"`.** The
   agent finds the replay later with `search_requests`, its row's
   `replay_of` the asked id. Refused at once, nothing waiting: an unknown
   exchange, a connection, a WebSocket or proxy exchange (as the window,
   which shows no button for them), an ended session, a device without
   `interaction`, and more than 20 replays already waiting (an agent in a
   loop cannot bury the window). The same exchange asked again while it
   waits is the same waiting replay. Annotated writing, destructive and
   open world: a confirmed `POST` runs again on a server outside the
   desktop.
2. **The call waits for the click and answers what happened.** It could
   outlast the client's timeout, and the stateless server cannot answer
   later.

### D. How MCP rows show a replay

1. **`replay` (true or false) and `replay_of`, the original's exchange
   id in the same session**, null once that row is gone, in
   `search_requests` and `get_request`. The agent knows exchange ids, not
   device request ids. `search_requests` resolves a page's replays in one
   read (`Storage.replayOriginals`).
2. **`replay_of` as the device's request id.** No tool shows request
   ids; the agent could not match it.

## Decision

A.1, B.1, C.1, D.1. Technical choices inside ADR-0111 E.1 and ADR-0120.

## Consequences

- No agent's replay reaches a device before a click, and none survives
  its session or a restart.
- The agent does not learn that the developer refused, or that the
  device sent a `ReplayRefused`: it sees no replay row. SDD 10 *Limits*
  says so.
- `McpServer` takes an optional `ReplayAsks`; without one (tests, a
  server built elsewhere) `replay_request` answers "replay is not
  available".
