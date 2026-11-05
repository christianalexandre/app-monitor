---
id: DEBT-0020
title: A device sends its traffic to any desktop that advertises the service
status: Paid
date: 2026-10-22
trigger: Before the first public release of an SDK
paid_date: 2026-10-31
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

ADR-0168, PLAN-0015 H5.4. The desktop keeps a random id in its store and
sends it in the TXT key `id` and in `HelloAck.desktop_id`; the SDK pins
the first id it gets from a desktop found by discovery, in a file beside
its device id, and from then on skips other ids in mDNS and closes a
session whose `HelloAck` names another. A manual `host` neither checks nor
pins. Left open, tied to the plain `ws://` transport (ADR-0018): the first
connection of an install, and a host on the desktop's own LAN that copies
the id from its TXT record. One device paired with two desktops of one
developer is DEBT-0115.
