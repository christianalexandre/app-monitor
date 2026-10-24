---
id: DEBT-0033
title: Non-correctness findings of the second M1 milestone review
status: Open
date: 2026-10-23
trigger: The next PR that touches one of the files listed below
paid_date:
---

# DEBT-0033 — Non-correctness findings of the second M1 milestone review

## What is missing

Findings of the `code-review` of `1896eb4..f90e316` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- `desktop/app/.../ui/BodyFormat.kt`: since M1.R1 a chunk is cut only at a
  line break, so it may reach about twice `CHUNK_CHARS`, and a long line
  after short ones is cut with them in one item of up to ~16 K characters.
  Layout cost per item doubles in the worst case; nothing is lost.

## Why it was accepted

None of them gives the developer wrong data or breaks the app; each is a
contained change best made beside the next edit of its file instead of a
standalone PR.

## Trigger

The next PR that touches `BodyFormat.kt` pays its item. The OkHttp
Capture.kt items are paid (below), so that name no longer triggers this.

## How it was paid

Partly. The `Capture.kt` items were paid by PLAN-0002 M1.R5
(`fix/mask-urls-in-response-headers`): a masked URL reaches the desktop with
`██`, not its percent-encoding, user name included, and `Masking.url` makes
one builder pass. The `BodyFormat.kt` item is open.
