---
id: DEBT-0008
title: Accepted channels are not enforced after the handshake
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: The first channel beyond `core` and `network` enters the protocol, or the M1 SDK sends envelopes
paid_date:
---

# DEBT-0008 — Accepted channels are not enforced after the handshake

## What is missing

The server answers `Hello` with the channels it accepts (`core`, `network`)
in `HelloAck`, then delivers every envelope to the session whatever its
channel. A device that sends `logs/*` gets those envelopes into the store and
the UI although the handshake refused that channel.

## Why it was accepted

Only the fake client connects in M0, and it replays fixtures of the two
supported channels. Whether an unaccepted channel is dropped, counted or
closes the socket is a protocol rule best written with the first real SDK.

## Trigger

The first channel beyond `core` and `network` enters the protocol, or the M1
SDK starts sending envelopes to the desktop.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
