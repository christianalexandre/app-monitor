---
id: DEBT-0095
title: The macOS title bar follows a theme picked in Settings only after a relaunch
status: Open # Open | Paid | Dropped
date: 2026-10-26
trigger: Someone reports the title bar's color not matching after picking Light or Dark in Settings
paid_date:
---

# DEBT-0095 — The macOS title bar follows a theme picked in Settings only after a relaunch

## What is missing

ADR-0150 sets AppKit's appearance once, at launch. Picking Light or Dark in
Settings repaints the window's content at once but leaves the title bar,
menus and system dialogs as they were until the app starts again. System
follows the system's appearance live.

## Why it was accepted

The reported bug was the default (System) case; the live alternative
(a transparent title bar of our own, ADR-0150 option 2) cannot be checked
from a test and changes the window's chrome.

## Trigger

Someone reports the title bar's color not matching after picking Light or
Dark in Settings.
