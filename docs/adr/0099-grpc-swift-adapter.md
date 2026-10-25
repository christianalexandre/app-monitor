---
id: ADR-0099
title: Which grpc-swift the adapter targets, and how an app adds it
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0099 — Which grpc-swift the adapter targets, and how an app adds it

## Context

PLAN-0009 M8.1 (a). ADR-0004 layer 2 is "interceptors for libraries the
app owns (grpc-swift `ClientInterceptor`, Ktor plugin)". The adapter is
new public API of the iOS SDK: an app developer adds a product and a line
of code (`AGENTS.md`, "stop and ask" 2). The SDK supports iOS 15 and
macOS 12 (`sdk/ios/Package.swift`).

Evidence, from the official repositories on 2026-10-25:

- **gRPC Swift 2** (`grpc/grpc-swift-2`, Apache-2.0, latest 2.4.4 of
  2026-10-13) is "the current major version". Its `Package.swift` sets
  the availability `macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0,
  visionOS 2.0` and `swift-tools-version:6.1`. Its `ClientInterceptor`
  (`Sources/GRPCCore/Call/Client/ClientInterceptor.swift`) is one
  `async` method over `StreamingClientRequest<Input>` and
  `StreamingClientResponse<Output>`, registered once on the `GRPCClient`.
  Messages arrive typed, not serialized.
- **gRPC Swift 1** (`grpc/grpc-swift`, branch `release/1.x`, latest
  1.27.6 of 2026-09-01) "is now in maintenance mode. Only bug fixes and
  security fixes will be applied", and its supported Swift versions
  shrink with each release, unsupported from Swift 6.4 (its README). Its
  `ClientInterceptor<Request, Response>` is an open class registered per
  service through a generated interceptor factory.
- SwiftPM resolves package dependencies per target (SE-0226), so an
  app that only uses the `AppMonitor` product does not build a
  dependency only another product's target uses.

Already decided, not reopened here: the exchange shape and
`capture_layer = ADAPTER` (ADR-0038); debug only, release builds hold no
SDK code (ADR-0048, standing owner decision); masking as today (ADR-0034).

## Options

### A. Which grpc-swift

1. **gRPC Swift 2 only.** The current major, one interceptor registered
   once per client. The adapter is available on iOS 18 and macOS 15 and
   up; an app on gRPC Swift 1 gets nothing from layer 2 and relies on
   layers 3 and 4.
2. **gRPC Swift 1 only.** Reaches apps on older OS versions, on a major
   in maintenance whose Swift support ends at 6.4; one factory per
   service in the app.
3. **Both, as two products.** Every app on grpc-swift covered; two
   adapters, two test servers and two API dumps to keep, one of them on
   a branch going out of support.

Recommended: 1.

### B. Where the adapter lives and the setup step

1. **A second product `AppMonitorGRPC` in the same package** (both
   manifests, ADR-0054), depending on `grpc-swift-2`; the app adds the
   product and passes `AppMonitorGRPCInterceptor()` in the client's
   `interceptors`. One dependency line and one argument, a step only
   gRPC apps take; apps without it do not build grpc-swift (SE-0226).
2. **A separate package in its own repository** — its own tags and
   versions, a second repository to release (M10) for one file.
3. **Inside `AppMonitor` behind `#if canImport(GRPCCore)`** — no new
   product, but SwiftPM cannot make a dependency optional, so every app
   would resolve grpc-swift.

Recommended: 1. It adds a step, but only to gRPC apps and only the one
the library itself asks for (registering an interceptor): no option
captures grpc-swift without it.

### C. What a call becomes

1. **One `ADAPTER` exchange per RPC**: method path as the URL
   (`grpc://host:port/package.Service/Method`), metadata as headers,
   each message serialized back with SwiftProtobuf when it is a
   `SwiftProtobuf.Message` (else its size only, body marked unread),
   unary request and response as bodies, streamed messages as frames,
   `grpc-status` and `grpc-message` as status and trailers. The desktop
   shows bytes, not decoded fields (no `.proto` on the desktop).
2. **Metadata only** — method, status, timing, no messages; smaller, and
   the debugging value of layer 2 over layer 3 is gone.

Recommended: 1.

## Decision

Every recommended option: A.1 (gRPC Swift 2 only), B.1 (a second
product `AppMonitorGRPC` in the same package) and C.1 (one `ADAPTER`
exchange per RPC). Accepted by the agent on the owner's standing instruction of 2026-10-25
(take the recommended options while the owner is away).

## Consequences

- A.1 leaves gRPC Swift 1 apps to layers 3 and 4; SDD 07 and the README
  say so.
- B.1 adds grpc-swift-2 and its transitive SwiftNIO packages to the
  package graph; `make publish-dry-run`'s consumer builds both with and
  without the product.
- C.1 serializes each message a second time on the device, bounded by
  `maxBodyBytes` like every body (ADR-0020).
