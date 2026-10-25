---
id: DEBT-0082
title: A mock rule cannot be moved in its app's match order
status: Open
date: 2026-10-25
trigger: The first PR that adds a query on `Mock.sq`'s `position`, or a rule a developer needs ahead of an earlier one
paid_date:
---

# DEBT-0082 — A mock rule cannot be moved in its app's match order

## What is missing

The first active rule that matches a call answers it (ADR-0110 B.1), and
a rule goes last when made (ADR-0113). The window has no "move up" or
"move down", and `Mock.sq` no query that changes `position`. Symptom: a
new, narrower rule never answers because an older, wider one matches the
same call first.

## Why it was accepted

M9.3 delivers making, confirming, editing and deleting rules; one rule
per endpoint, the common case, needs no order. Deleting the wider rule
and making it again puts it last (ADR-0114).

## Trigger

The first PR that adds a query on `Mock.sq`'s `position`, or the first
time a developer needs a rule ahead of an earlier one.

## Breakpoints too

M9.8 (ADR-0119): the Breakpoints panel has no order either, and
`Breakpoint.sq` no query that changes `position`; the first enabled
breakpoint that matches holds the call. Paid with the mocks, by the same
trigger.

## How it was paid

> Filled when closing.
