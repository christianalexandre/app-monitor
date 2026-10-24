---
id: ADR-0083
title: Device approvals survive a restart, and how one is revoked
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0083 — Device approvals survive a restart, and how one is revoked

## Context

PLAN-0006 M5.1 (c). ADR-0018 E: approval per (`device_id`, `app_id`) before
`HelloAck`, reject closes with 1008, decisions kept in memory until the app
quits, "persisted in M5". ADR-0027 put it in `DeviceApprovals`, a hook on
`AppMonitorServer`. Which devices the desktop admits without asking is a
"stop and ask" area (`AGENTS.md`, security / privacy).

Already decided, not reopened here:

- The key is (`device_id`, `app_id`); `device_id` is a random UUID per
  install that the device reports itself (ADR-0018 D). The desktop has no
  proof of it: any host on the LAN that learns a `device_id` and `app_id`
  can present them. They travel in `Hello` over plain `ws://` (ADR-0018 F,
  DEBT-0020).
- A rejected pair is closed at once on reconnect; the SDK backs off.

## Options

### A. What is stored

1. **Approved and rejected pairs**: `device_id`, `app_id`, the decision,
   when it was taken, and the last `device_name`, OS and app version seen
   (to show the developer which device a row is). Nothing else from the
   device.
2. **Approved pairs only** — a rejected device asks again after every
   restart; with the SDK's backoff that is a dialog per restart for a
   device the developer already refused.

Recommended: 1.

### B. How long an answer lasts

1. **Until revoked**, no expiry. The weakness is the one already accepted:
   a forged `Hello` with a known pair is let in, today for the length of a
   desktop session, after this for as long as the pair is stored. What a
   forger gains is writing traffic into the developer's desktop, not
   reading it.
2. **Expire after N days** (e.g. 30) — bounds the forgery window; the
   developer re-approves every device every month.

Recommended: 1.

### C. Revoking

1. **A "Devices" section in the settings** listing every stored pair
   (device name, app id, decision, date) with "Forget" per row and
   "Forget all"; a forgotten pair is asked again on its next `Hello`, and
   its open sessions are closed with 1008 when it was approved. "Clear all
   data" (ADR-0082 D) forgets them too.
2. **Revoke from the device list only** (right click on a device) — no
   way to reach a rejected pair, which never appears in that list.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- M5.5 tests: approved, store reopened, admitted with no prompt; rejected,
  reopened, closed at once; forgotten, asked again.
- B.1 extends DEBT-0020's exposure from a desktop session to the life of
  the stored pair; its trigger (first public SDK release) is unchanged.
- The headless server (`make server`) keeps approving all and stores
  nothing (ADR-0027).
