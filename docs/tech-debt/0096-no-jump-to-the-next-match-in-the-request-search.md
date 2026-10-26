---
id: DEBT-0096
title: The search inside a request has no jump to the next match
status: Open # Open | Paid | Dropped
date: 2026-10-26
trigger: Someone searches a body longer than the detail pane and asks to be taken to the matches
paid_date:
---

# DEBT-0096 — The search inside a request has no jump to the next match

## What is missing

ADR-0152's search highlights every match in the shown tab and counts them
per tab, but nothing scrolls to a match: in a body longer than the pane
the user scrolls to the highlights by hand.

## Why it was accepted

The bodies are lazy rows of chunks (ADR-0029); jumping means mapping a
match to its chunk and its line inside it, then scrolling the list there.
The count per tab already says where to look, and the field test had not
yet shown long bodies being searched.

## Trigger

Someone searches a body longer than the detail pane and asks to be taken
to the matches.
