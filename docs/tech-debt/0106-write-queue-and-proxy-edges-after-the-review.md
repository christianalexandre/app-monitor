---
id: DEBT-0106
title: Four edges left in the write queue and the proxy after #313
status: Open
date: 2026-10-30
trigger: "The next PR that touches `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ProxyCa.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/ProxyController.kt` pays the items in that file"
paid_date:
---

# DEBT-0106 — Four edges left in the write queue and the proxy after #313

## What is missing

The review of #313 accepted its fixes and found four small edges:

- **Enqueue races a dead writer** (`WriteQueue.kt`, `enqueue`). Between
  its `trySend` and its `queuedBytes` update, the writer can end on an
  `Error` and zero `queuedBytes`. The item's bytes are then added after
  that and never taken off, so a later `enqueue` that does not fit waits
  forever.
- **Items left by a dead writer are not counted** (`WriteQueue.kt`,
  `drain`). They go to `dropped` but not to `notSaved` or `pending`, so
  `close()` reports 0 lost.
- **An expired CA keeps minting** (`ProxyCa.kt`). The CA is replaced
  only at `loadOrCreate`. With the proxy running past the CA's end, each
  handshake mints a leaf that is already expired.
- **The stored switch can differ from memory** (`ProxyController.kt`,
  `setEnabled`, as in `McpController`). `setSetting(KEY_ENABLED, on)`
  writes the call's own `on` outside both locks. Two calls at once can
  leave the store holding the other value from the one the proxy runs
  with, until the next switch.

## Why it was accepted

Each one needs an `Error` thrown inside a write (out of memory, an
assertion), a proxy left on past its CA's 365 days, or two switches in
the same instant. #313 fixed the hang and the leaf churn that normal use
can hit. The reviewer accepted these as one item, so the merge is not
held back.

## Trigger

The next PR that touches
`desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ProxyCa.kt` or
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/ProxyController.kt`
pays the items in that file.

Both `WriteQueue.kt` items are paid by PLAN-0016 H6.2a
(`anEnqueueRacingADeadWriterDoesNotWaitForGood`,
`itemsLeftByADeadWriterAreCounted`, each failing on the old code). The other
two are placed in PLAN-0016's H6.2.

## How it was paid

> Filled when closing.
