---
id: DEBT-0115
title: A device trusts one desktop, never two of one developer
status: Open
date: 2026-10-31
trigger: A developer pairs one device with two desktops (macOS, Linux, Windows) and reports having to clear the app's data to switch
paid_date:
---

# DEBT-0115 — A device trusts one desktop, never two of one developer

## What is missing

A device pins the first desktop that approved it (ADR-0168). A developer
with two desktops (a Mac at the office, a Linux at home) cannot let one
device reach both: moving it means clearing the app's data, reinstalling,
or setting `AppMonitorOptions.host`. The desktop cannot take another
desktop's id.

## Why it was accepted

The owner's answer B to ADR-0168 (2026-10-31): no case yet, and a shared
id needs a Settings entry and an explanation of what it exposes.

## Trigger

A developer pairs one device with two desktops and reports having to clear
the app's data to switch. The change would touch
`desktop/app/src/main/kotlin/appmonitor/desktop/app/Main.kt`, where the
id is created.

## How it was paid

> Filled when closing.
