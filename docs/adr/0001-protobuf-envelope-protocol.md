---
id: ADR-0001
title: Protobuf protocol with a channel envelope
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0001 — Protobuf protocol with a channel envelope

## Context

SDKs in Swift and Kotlin must talk to a Kotlin/JVM desktop. The protocol is the
long-lived contract of the project: new SDKs (Flutter, React Native) and new
debug tools (logs, analytics, storage) must plug in without breaking existing
clients. Payloads include binary bodies.

## Options

1. **JSON over WebSocket** — easy to debug, no codegen; untyped across
   languages, base64 bloat for bodies, schema drift is silent.
2. **Protobuf over WebSocket** — typed, compact, explicit evolution rules
   (never reuse field numbers); needs codegen per language.
3. **Custom binary framing** — maximal control, no tooling, high maintenance.

## Decision

Protobuf messages over WebSocket binary frames, every message wrapped in an
`Envelope { channel, type, payload, timestamp }`. Kotlin code generated with
Square **Wire** (KMP support, no `protoc` needed); Swift with
**swift-protobuf**. A `Hello` handshake carries protocol version, platform, app
id and supported channels. The protocol is bidirectional from the start.

## Consequences

- New debug tools add a channel, not a transport.
- Contract fixtures (Kotlin ↔ Swift) are mandatory for protocol changes
  (guidelines 04).
- Two codegen toolchains to keep in sync with one `.proto` source.
