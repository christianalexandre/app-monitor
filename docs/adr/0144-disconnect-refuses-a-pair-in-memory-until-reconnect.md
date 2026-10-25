---
id: ADR-0144
title: Disconnect refuses a pair in memory, before approval, until Reconnect
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0144 — Disconnect refuses a pair in memory, before approval, until Reconnect

## Context

ADR-0140 E: a device in the list has "Disconnect"; the desktop closes its
session and refuses it, without a new approval prompt, until "Reconnect".
No SDK change: the SDK's backoff keeps retrying and is refused as a
rejected device is today (ADR-0083). What is already there:

- `DeviceApprovals` (`desktop/server`) remembers one answer per
  (`device_id`, `app_id`) and `forget` drops it, so the next `Hello` asks
  again (SDD 01).
- `AppMonitorServer.disconnect(key)` closes every connection of a pair
  with 1008, handshakes in flight included (SDD 01, its test
  `a forgotten device is closed with 1008 and asked again`).
- A rejected pair is closed with 1008 at once on every `Hello`
  (`a rejected device is closed with 1008, and at once when it comes back`).
- The live list drops a closed session that holds no exchange, and Clear
  drops every closed session (`LiveTraffic.disconnected`, `clear`).

Open points: where the refusal lives, whether it outlives a restart, and
where "Reconnect" is when the session it was started from is gone.

## Options

1. **Store a rejection.** Disconnect = `decide(key, false)` stored;
   Reconnect = `decide(key, true)`. No new state, but it erases the
   developer's answer (a pair never approved would be approved by
   Reconnect) and the Settings list would show it as "rejected".
2. **A disconnected set beside the answers, in memory.** `DeviceApprovals`
   checks it before the answers and refuses without asking; Reconnect
   removes the key and the answer applies again. Lost on restart.
3. **Option 2, persisted** in a new table or setting. Survives a restart,
   at the cost of a migration and of a pair refused forever if the
   developer forgets about it.

## Decision

Option 2. `DeviceApprovals.disconnect(hello)` adds the pair, with its
`Hello` for the list, to `disconnected` and answers its connections still
pending with a refusal it does not remember; `approve` refuses a pair in
it before reading the answers; `reconnect(key)` removes it. The app's
`ApprovalStore` serves it as the domain's `DeviceConnectionRepository`:
refuse first, then `AppMonitorServer.disconnect`, so a `Hello` racing the
close is refused. The window lists disconnected pairs in their own
"Disconnected" section with Reconnect, since the session row may be gone
from the live list.

## Consequences

- A restart reconnects every pair; disconnecting is a session-long
  choice, as ADR-0140 E describes a live action. Persisting it would be a
  new table and a new ADR.
- A disconnected device retries with its backoff and is closed with 1008
  each time, as a rejected device is; no SDK or protocol change.
- The answer in Settings stays what the developer gave; Forget on a
  disconnected pair drops the answer but it stays refused until Reconnect.
