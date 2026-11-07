---
id: DEBT-0132
title: Windows and Linux keep the system's title bar over the window's own
status: Open # Open | Paid | Dropped
date: 2026-11-07
trigger: The owner runs the desktop on Windows, or a PR changes `MacTitleBar.kt` or the JDK the desktop runs on
paid_date:
---

# DEBT-0132 — Windows and Linux keep the system's title bar over the window's own

## What is missing

On macOS the traffic row is drawn in the title bar's place (ADR-0213). On
Windows and Linux the system's title bar stays, and the row sits in a second
bar under it, as ADR-0205 drew it. The symptom is two bars at the top of the
window, the upper one saying only "App Monitor".

## Why it was accepted

The owner picked macOS only, on any JDK, on 2026-11-07 (PR #401). Without the
JetBrains Runtime's custom title bar, AWT has no way to draw content in a
Windows or Linux title bar. The other way is an undecorated window, which loses
the system's resize edges and snapping.

## Trigger

The owner runs the desktop on Windows, or a PR changes `MacTitleBar.kt` or the
JDK the desktop runs on (a move to the JetBrains Runtime opens its custom title
bar on every OS).

## How it was paid

> Filled when closing.
