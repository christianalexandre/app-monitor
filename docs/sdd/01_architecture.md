# 01 — Architecture

The target architecture agreed before the first line of code. Parts not yet
built are marked **(planned)**; see `../plans/` for their milestone.

## Overview

```
┌──────────── Device / emulator ────────────┐        ┌──────────── Desktop (Compose MP / JVM) ─────────────┐
│ Developer's app                            │        │                                                     │
│  ├─ sdk-ios (Swift)                        │  WS +  │  Server (Ktor WebSocket) ──► Ingest ──► SQLite      │
│  ├─ sdk-android (OkHttp)                   │ ─────► │        ▲ advertises mDNS       │       (SQLDelight) │
│  └─ sdk-ktor (KMP)                         │ proto  │        │                       ▼                    │
│        └── sdk-core (KMP) / protocol       │        │  Compose UI ◄── StateFlow    MCP server (localhost) │
└────────────────────────────────────────────┘        └──────────────────────────────▲──────────────────────┘
                                                                  AI agents (Claude…) ┘
```

All parts are **(planned)**.

## Protocol

Protobuf messages wrapped in an `Envelope` with a `channel` (`network`, later
`logs`, `analytics`, `db`, …) so new debug tools plug in without changing the
transport. Generated with Wire (Kotlin) and swift-protobuf (Swift).
Bidirectional from day one. See ADR-0001.

## Connection

The **desktop is the server**; devices are clients. Discovery order on the
device: manual host → mDNS (`_appmonitor._tcp`) → `10.0.2.2` (Android
emulator) / `localhost` (iOS simulator). See ADR-0002.

## SDKs

- **iOS**: native Swift package, no Kotlin runtime. See ADR-0003.
- **Android / KMP**: `sdk-core` in KMP; adapters for OkHttp and Ktor.
- Capture coverage on iOS is layered (URLSession → adapters → connection
  metadata → desktop MITM proxy). See ADR-0004.

## Desktop

Compose Multiplatform on the JVM. Ktor server + JmDNS, SQLDelight storage,
MCP server bound to `127.0.0.1`. Each debug tool is a feature module owning a
`channel`. See ADR-0005.

## Limits

Debug builds only; release builds use no-op artifacts. Device and desktop must
share a network (or USB/emulator loopback).
