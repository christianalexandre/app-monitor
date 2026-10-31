---
id: DEBT-0024
title: AppMonitorOptions.appName is accepted but never sent
status: Paid # Open | Paid | Dropped
date: 2026-10-22
trigger: The desktop shows an app by name (M1.9 approval dialog or later), or `Hello` gains an app name field
paid_date: 2026-10-31
---

# DEBT-0024 — AppMonitorOptions.appName is accepted but never sent

## What is missing

ADR-0017 B puts `appName` ("shown in the desktop") in `AppMonitorOptions`,
but `Hello` (`protocol/proto/appmonitor/v1/core.proto`) has no field for it.
The SDK takes the option and drops it; the desktop only sees `app_id`.

## Why it was accepted

M1.6 ships the options as the owner accepted them, so `sdk/no-op` (M1.8)
mirrors a stable surface. Carrying the name is a protocol change (new field,
fixtures) with no consumer yet: the M1.9 dialog is specified with `app_id`
(ADR-0018 E).

## Trigger

The desktop shows an app by name (the M1.9 approval dialog or a later
view), or `Hello` gains an app name field.

## How it was paid

PLAN-0015 H5.3a (ADR-0165): `Hello.app_name = 11`, sent by `sdk/core`
(Android and iOS) and the Swift SDK from `AppMonitorOptions.appName`. The
desktop names the app by it, else by `app_id`, in the approval dialog and
the devices pane; the end to end scripts assert each sample's name. The
whole `Hello` was already stored, so no migration was needed. Left: the
MCP tools and the runs dialog still list `app_id` only.
