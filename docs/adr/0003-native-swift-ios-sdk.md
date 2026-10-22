---
id: ADR-0003
title: Native Swift iOS SDK, no Kotlin runtime
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0003 — Native Swift iOS SDK, no Kotlin runtime

## Context

Most iOS apps are pure Swift. Interception on iOS relies on Objective-C runtime
features (URLProtocol registration, method swizzling) and Apple frameworks
(Network.framework). The previous tool (`app-network-monitor`) is Swift and
depends on Pulse for in-app UI, which is no longer needed.

## Options

1. **Ship `sdk-core` (KMP) as an XCFramework** — one implementation of
   transport; adds the Kotlin/Native runtime (several MB) and awkward Swift
   APIs to every app.
2. **Native Swift package** — idiomatic API, small binary, direct access to
   the runtime; transport implemented twice.

## Decision

The iOS SDK is a native Swift package (SwiftPM), built on Network.framework
and swift-protobuf, with no Pulse dependency. Compatibility with Kotlin SDKs is
guaranteed by the shared `.proto` and contract fixtures (ADR-0001).

KMP apps on iOS use `sdk-ktor`; duplicates with the Swift SDK are avoided by a
correlation header that the Swift SDK ignores.

## Consequences

- Two transport implementations to keep behaviorally equal.
- The Swift SDK can be adopted by apps with no Kotlin at all.
