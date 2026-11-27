---
id: DEBT-0139
title: The desktop's packages are never launched before they are released
status: Open
date: 2026-11-26
trigger: The `release/desktop-1.2.0` branch is cut, or a released package that does not start
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

The `release/desktop-1.2.0` branch is cut, or a released package that does not start for
another reason.

Re-triggered on 2026-11-26, at `release/desktop-1.1.0`: the desktop skipped `1.0.1`, so
the fix release is `1.1.0`. ADR-0232's PR changed the `desktop` job's conditions only, not
how it builds. Its Linux and Windows packages were installed and opened by hand, the macOS
`.dmg` built from the release branch launched; each is opened again from the release.

## How it was paid

> Filled when closing.
