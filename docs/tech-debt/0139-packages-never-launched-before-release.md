---
id: DEBT-0139
title: The desktop's packages are never launched before they are released
status: Open
date: 2026-11-26
trigger: The next PR that changes the `desktop` job of `.github/workflows/publish.yml`, or a released package that does not start
paid_date:
---

# DEBT-0139 — The desktop's packages are never launched before they are released

## What is missing

The publish workflow builds the `.dmg`, `.msi`, `.zip`, `.deb` and `.rpm` and attaches them
to the release without starting any of them. The `1.0.0` packages failed at start on every
OS (ADR-0230) and nothing in CI noticed. Symptom: *Failed to launch JVM* on Windows, an app
that closes at once on macOS.

## Why it was accepted

ADR-0230's check covers the failure that happened, a module missing from the runtime, for
about 15 s per `make check`. Launching each package needs a display on Linux (Xvfb) and a
way to tell "started" from "crashed" without a window test on three OSes; that is more than
the `1.0.1` fix needs.

## Trigger

The next PR that changes the `desktop` job of `.github/workflows/publish.yml`, or a released
package that does not start for another reason.

## How it was paid

> Filled when closing.
