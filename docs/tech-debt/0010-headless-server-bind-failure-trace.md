---
id: DEBT-0010
title: Headless server prints a stack trace when the port is taken
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: The headless server is started by something other than a developer at a terminal (a script, CI, the MCP tooling), or someone reports the trace as a bug
paid_date:
---

# DEBT-0010 — Headless server prints a stack trace when the port is taken

## What is missing

`make server` validates `--port` and exits 2 with one line, but a valid port
that cannot be bound (already in use, no permission) still escapes `main`
from `AppMonitorServer.start` as a stack trace. The fake client and the
desktop app already report such failures in one line.

## Why it was accepted

M0.R11 was scoped to argument parsing. The headless server is a developer
tool run by hand; the trace names the cause (`BindException`), and the port
is freed by `start` before it rethrows.

## Trigger

The headless server is started by something other than a developer at a
terminal (a script, CI, the MCP tooling), or someone reports the trace as a
bug.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
