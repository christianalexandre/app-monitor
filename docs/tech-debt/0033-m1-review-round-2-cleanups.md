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

- `sdk/android-okhttp/.../Capture.kt`: `HttpUrl.Builder` percent-encodes the
  mask, so a masked URL password or query parameter reaches the desktop as
  `%E2%96%88%E2%96%88`, not the `██` SDD 05 describes. The secret is still
  hidden; only the shown value differs from the doc.
- `sdk/android-okhttp/.../Capture.kt`: `Masking.url` has three exit paths
  where one builder pass would do.
- `desktop/app/.../ui/BodyFormat.kt`: since M1.R1 a chunk is cut only at a
  line break, so it may reach about twice `CHUNK_CHARS`, and a long line
  after short ones is cut with them in one item of up to ~16 K characters.
  Layout cost per item doubles in the worst case; nothing is lost.

## Why it was accepted

None of them gives the developer wrong data or breaks the app; each is a
contained change best made beside the next edit of its file instead of a
standalone PR.

## Trigger

The next PR that touches `Capture.kt` or `BodyFormat.kt` pays that file's
items.

## How it was paid

> Filled when closing.
