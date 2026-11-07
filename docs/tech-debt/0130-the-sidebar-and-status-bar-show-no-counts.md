---
id: DEBT-0130
title: The sidebar and the status bar show no counts
status: Open # Open | Paid | Dropped
date: 2026-11-06
trigger: The owner names a missing count in PLAN-0018 H8.6, or a PR adds a grouped count query to `desktop/storage/src/main/sqldelight`
paid_date:
---

# DEBT-0130 — The sidebar and the status bar show no counts

## What is missing

The mockup (`docs/screenshots/h8-mockup.html`) shows a count beside "All
devices", each device, each run and each host, and "Recording · N requests" in
the status bar. The window shows none of them (ADR-0205): the sidebar rows have
no number and the status bar says "Recording since HH:mm".

## Why it was accepted

The store has no count query: the window reads exchanges a page at a time and
the hosts as a plain list. Counting the rows read would be wrong past one page
and for every device but the one picked. PLAN-0018 leaves the store alone.

## Trigger

The owner names a missing count while comparing the window with the mockup in
PLAN-0018 H8.6, or a PR adds a grouped count query to
`desktop/storage/src/main/sqldelight`.

## How to pay

A store query counting exchanges per session and per host for a run (and the
total), observed like `observeHosts`; `TrafficState` carries the counts and
`SidebarItem`'s trailing slot and the status bar show them.
