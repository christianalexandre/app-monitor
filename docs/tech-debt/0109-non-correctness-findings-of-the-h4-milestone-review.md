---
id: DEBT-0109
title: Findings of the H4 milestone review that normal use does not hit
status: Open
date: 2026-10-30
trigger: "The next PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`, `sdk/core/src/androidMain/kotlin/appmonitor/sdk/core/LocalNetworkPermission.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/ProxyController.kt`, `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ProxyCa.kt`, `sdk/ios/Sources/AppMonitor/Breakpoints.swift`, `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ExportHar.kt` or `sdk/ios/Sources/AppMonitor/ProtoWire.swift`"
paid_date:
---

# DEBT-0109 — Findings of the H4 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0014 (`8ab8368..19f771a`) found these.
None of them is hit in normal use (PLAN-0014, *H4 milestone review*):

1. `WriteQueue`: a writer that ends on an `Error` hands its items to
   `dropped` without subtracting them from `pending`, so `close` reports
   0 lost and the *not saved* count misses them.
2. `LocalNetworkPermission`: the SDK's `requestPermissions` can meet an app
   request made while its dialog is up. Android serves one set at a time,
   and the other request gets a cancelled result, in debug builds only.
3. `ProxyController.setEnabled`: two quick, concurrent toggles can store
   the value of the first while the switch keeps the second.
4. `ProxyCa`: once the running CA is past `notAfter`, every leaf is
   already expired, and each handshake mints a new key pair inside
   `compute`.
5. `Breakpoints.swift`: a response held early reports
   `expectedContentLength`, the wire size of a compressed body, not the
   decoded size the app receives.
6. `ExportHar.webSocketMessages`: with `bodies: full`, a whole page of
   frames is built before the size check.
7. `ProtoWire.swift`: every nested message is copied once per level on
   read and on write.
8. 85 source comments cite `SDD NN`, which guideline 02 does not allow
   (only `ADR-NNNN` or `DEBT-NNNN`). The two this milestone added were
   fixed in its close.

## Why it was accepted

1, 3 and 4 need rare timing or an `Error`, two toggles at once, or a
desktop left running past its CA's validity. 2 needs the app to ask for
a permission while the SDK's dialog is showing. A request made on resume
pauses the Activity before the SDK's 500 ms settle. 5 needs a breakpoint
on a compressed response past the capture limit. 6 needs frames of
several MiB exported in full. 7 is cost, not wrong data. 8 is the wording
of comments.

## Trigger

The next PR that touches one of the files above. Then fix the finding of
that file, or say why not in the PR. Item 8 is paid one file at a time,
whenever a PR touches a comment that cites an SDD.

## How it was paid

> Filled when closing.
