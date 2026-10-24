---
id: DEBT-0020
title: A device sends its traffic to any desktop that advertises the service
status: Open
date: 2026-10-22
trigger: Before the first public release of an SDK
paid_date:
---

# DEBT-0020 — A device sends its traffic to any desktop that advertises the service

## What is missing

Device approval (ADR-0018) protects the desktop, not the device: the SDK
connects to the first `_appmonitor._tcp` it finds, and mDNS cannot tell a
trusted desktop from any other host on the LAN (RFC 6762 §21). There is no
pairing code and no desktop fingerprint pinned on the device.

## Why it was accepted

M1 runs in debug builds on the developer's own LAN, with secret headers
masked on the device; pairing costs UI on both ends before the transport
even exists.

## Trigger

Before the first public release of an SDK.

## How it was paid

> Filled when closing.
