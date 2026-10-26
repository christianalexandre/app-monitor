---
id: DEBT-0097
title: The issues UI test missed its "Resolved" chip once
status: Paid # Open | Paid | Dropped
date: 2026-10-26
trigger: "`IssuesUiTest` fails again, locally or on CI"
paid_date: 2026-10-26
---

# DEBT-0097 — The issues UI test missed its "Resolved" chip once

## What is missing

On 2026-10-26, in a full `./gradlew :desktop:app:test` on
`feat/resizable-panes`, `IssuesUiTest` ("requests saved as an issue are
listed, filtered, edited and kept across a reopen") failed with "Failed to
inject mouse input … could not find any node … 'Resolved'". Three reruns
of that test alone and the next full run passed. The branch changes the
traffic panes and the window's minimum size, not the issues dialog.

The likely cause: the test clicks the chip without waiting for it, while
the dialog is still reading the store. Not confirmed.

## Why it was accepted

Seen once in four runs, unrelated to the change, every rerun green.

## Trigger

`IssuesUiTest` fails again, locally or on CI.

## How it was paid

Fired by the release gate of #293 on `windows-latest`: the same "could not
find any node … 'Resolved'" at `IssuesUiTest.kt:85`. The test clicked a
menu's item right after the click that opens the menu; on a slow runner
the menu had not opened yet. It now waits for each menu item ("checkout",
"Resolved", "Any") before clicking it.
