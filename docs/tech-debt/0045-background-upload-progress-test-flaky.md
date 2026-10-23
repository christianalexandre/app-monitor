---
id: DEBT-0045
title: The background upload test missed its progress callback once
status: Open # Open | Paid | Dropped
date: 2026-10-23
trigger: The second failure of `aFileUploadIsCapturedBothWays` in `make swift-sim`, or the next PR that touches `BackgroundSessionTests.swift`
paid_date:
---

# DEBT-0045 — The background upload test missed its progress callback once

## What is missing

`sdk/ios/TestHost/Tests/BackgroundSessionTests.swift`
`aFileUploadIsCapturedBothWays` failed once in `make check` on 2026-10-23
(iPhone 17 Pro simulator, branch `docs/release-gate-decisions`, a docs-only
change): `delegate.callbacks` was `["didReceiveData",
"didFinishCollecting", "didComplete"]`, without `didSendBodyData`
(line 175). The rerun of `make swift-sim` and the next full `make check`
passed.

## Why it was accepted

One failure. `URLSession` gives no guarantee that a small upload reports
progress at all, so the expectation may be wrong rather than the capture;
deciding that needs a second occurrence or Apple's documentation, not a
guess in a docs-only PR.

## Trigger

The second failure of this test, or the next PR that touches the file.
Then: check whether `didSendBodyData` is guaranteed for the test's body
size, and either drop that expectation or make the body large enough.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
