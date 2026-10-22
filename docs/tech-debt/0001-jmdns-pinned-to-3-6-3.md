---
id: DEBT-0001
title: JmDNS pinned to 3.6.3
status: Open
date: 2026-10-21
trigger: A JmDNS release newer than 3.6.4, or a security advisory for 3.6.3
paid_date:
---

# DEBT-0001 — JmDNS pinned to 3.6.3

## What is missing

`gradle/libs.versions.toml` pins JmDNS to 3.6.3, one release behind. With
3.6.4 the desktop does not answer mDNS queries on macOS: devices only find it
through the announcements sent at start-up, and the mDNS test in
`desktop/server` fails.

## Why it was accepted

3.6.3 works, and 3.6.4 has no fix this project needs. ADR-0009 has the
evidence (the bind-address change in `JmDNSImpl`).

## Trigger

A JmDNS release newer than 3.6.4 is published (re-run
`./gradlew :desktop:server:test` on it), or a security advisory affects
3.6.3. Either way, replacing JmDNS or reporting upstream may be the payment.

## How it was paid

> Filled when closing.
