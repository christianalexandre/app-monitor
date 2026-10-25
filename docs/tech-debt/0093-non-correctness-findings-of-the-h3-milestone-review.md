---
id: DEBT-0093
title: Findings of the H3 milestone review that normal use does not hit
status: Open
date: 2026-10-25
trigger: "The next PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/LiveTraffic.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Recorder.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/TrafficViewModel.kt` or `desktop/server/src/main/kotlin/appmonitor/desktop/server/DeviceApproval.kt`"
paid_date:
---

# DEBT-0093 — Findings of the H3 milestone review that normal use does not hit

## What is missing

From PLAN-0013's milestone review (`code-review` at `medium` of
`d4ccd48..d02b21e`), findings 3 to 10. None was checked by a test.

- **In-flight exchange trimmed.** `LiveTraffic.trim` drops the oldest
  exchange even while it is open (a WebSocket). Its later frames make a
  new row with no method, URL or host. Needs 10k exchanges or 128 MB
  after the socket opened (`LiveTraffic.kt`).
- **Saved while pending.** `Ingest`'s `Save` copies the live exchange as
  it is, and a second Save of it is skipped by `idOf`. A request saved
  before its response stays without it in Saved, and the second Save
  still says "Saved 1" (`Ingest.kt`).
- **Observers rescan on every envelope.** `LiveTraffic.change` bumps
  `changes` on early returns too (unknown session, other channel), so
  `observeExchanges` and `observeHosts` rescan up to 10k rows for each
  log or analytics envelope (`LiveTraffic.kt`).
- **Detail's session snapshot.** `TrafficViewModel.detail` reads
  `pickedSessionOf()` outside its `combine` inputs. A request picked
  before the sessions load keeps an empty `appId`: "Mock this" has no
  app, and the refused-replay note is not shown (`TrafficViewModel.kt`).
- **Marks left after Clear.** `clear()` unmarks only exchanges of the
  shown live sessions, so marks on a trimmed session's ids stay. "Save N"
  then counts them as not saved (`TrafficViewModel.kt`).
- **Recorder's request map.** `Recorder`'s `device.exchanges` keeps one
  entry per request started while recording until the session ends.
  It grows for a long recording, and a request id reused after Stop goes
  to the ended run (`Recorder.kt`).
- **Stored marks counted as failures.** `save()` compares `saved.size`
  with `ids.size`, but stored ids are dropped before saving. Marks made
  in "Saved" and kept into Live count as failures and stay
  (`TrafficViewModel.kt`).
- **KDoc blocks over four lines.** `TrafficState` (6 lines,
  `TrafficViewModel.kt`), `DeviceApprovals` (5, `DeviceApproval.kt`) and
  `Storage.clearAll` (5, `Storage.kt`) break guideline 02's limit.

## Why it was accepted

Each needs the live bound, a race with loading, an unusual sequence of
marks or a session open for hours. Findings 1 and 2 (a run's end moved
by the next Record and by close) are hit in normal use and were fixed by
H3.R1.

## Trigger

The next PR that touches one of the six files named in the frontmatter.
That PR fixes the items about the file it touches, or says why it does
not.

## How it was paid

> Filled when closing.
