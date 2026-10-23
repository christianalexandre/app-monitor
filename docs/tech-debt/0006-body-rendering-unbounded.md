---
id: DEBT-0006
title: Body rendering is synchronous and unbounded
status: Paid # Open | Paid | Dropped
date: 2026-10-22
trigger: The first SDK that captures real app bodies lands (M1), or a body freezes the detail pane
paid_date: 2026-10-22
---

# DEBT-0006 — Body rendering is synchronous and unbounded

## What is missing

`render` in `desktop/app/.../ui/BodyFormat.kt` runs inside composition, on the
UI thread, over the whole body. It is linear and memoized per body and format,
but a body of tens of MB still blocks the detail pane while it formats, and a
JSON body nested thousands of levels deep produces indentation that grows with
depth × lines. There is no size cap and no truncation. `utf8OrNull` and
`hexDump` each copy the whole body with `toByteArray`.

## Why it was accepted

M0 only shows fixture traffic, with bodies of a few KB. A 4 MB JSON body
formats well under the 5 s bound of `BodyFormatTest`. Truncation and
background formatting are UI choices better made against real SDK traffic.

## Trigger

The first SDK that captures real app bodies lands (M1), or a body is observed
freezing the detail pane.

## How it was paid

PLAN-0002 M1.10 (ADR-0029): bodies render on `Dispatchers.Default` and come
back as chunks of at most 100 lines or 8 192 characters, each an item of the
detail pane's `LazyColumn`, so the UI thread lays out only what is visible.
Indented JSON stops at 16 Mi characters; kotlinx-serialization already
refuses nesting past a few hundred levels. A UI test opens a body at the
protocol limit and fails if the click blocks for 2 s. Left: `toByteArray`
copies remain, off the UI thread now.
