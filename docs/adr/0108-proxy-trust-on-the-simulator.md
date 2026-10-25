---
id: ADR-0108
title: How a simulator's gRPC client trusts the proxy
status: Proposed # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0108 — How a simulator's gRPC client trusts the proxy

## Context

ADR-0101 decided the proxy's feed and its CA, and they stay as they are:
loopback only (A.1), traffic sent through it per app launch by the
`grpc_proxy` variable (B.1), one CA per data directory (C.1), every host
intercepted (E.1). Its D.1 adds a button that runs `xcrun simctl keychain
<udid> add-root-cert <ca.pem>` on each booted simulator. PLAN-0009 M8.8
builds that button and an iOS sample button whose gRPC call goes through
the proxy, checked by `make e2e-ios`.

Writing M8.8 showed that neither gRPC client on iOS reads the
simulator's keychain on the path B.1 opens:

- **gRPC Swift 2** (the library of ADR-0099 and `AppMonitorGRPC`) has no
  proxy support. `grpc-swift-nio-transport` 2.11.0 has no `proxy`,
  `grpc_proxy` or `http_proxy` anywhere in `Sources/`; neither transport's
  configuration takes `NWParameters` or a proxy, only a
  `channelDebuggingCallbacks.onCreateTCPConnection` that runs after the
  TLS and HTTP/2 handlers are in the pipeline
  (`HTTP2ClientTransport+Posix.swift`, `+TransportServices.swift`). Its
  calls are already captured in full by layer 2 (`AppMonitorGRPC`).
- **gRPC-Core** (gRPC-C++, Objective-C, and what Firebase builds on)
  follows `grpc_proxy`, then `https_proxy`, then `http_proxy`
  (`src/core/handshaker/http_connect/http_proxy_mapper.cc`, grpc/grpc at
  `03052d67`). Its TLS is BoringSSL with roots from
  `GRPC_DEFAULT_SSL_ROOTS_FILE_PATH`, else its bundled roots, else, on
  Apple, the file `/etc/ssl/cert.pem`
  (`src/core/config/config_vars.cc`,
  `src/core/credentials/transport/tls/load_system_roots_supported.cc`).
  The keychain is not read, so D.1's button does not make a gRPC-Core
  client accept the proxy's leaf.

So the only client B.1 feeds ignores what D.1 installs. A plaintext
gRPC-Core call through the proxy needs no CA at all.

Not verified yet: whether an app on the simulator can read a file under
the Mac's data directory, which option 1 relies on; the task that
follows this ADR checks it first.

## Options

1. **Trust by environment too; the keychain button goes.** The
   settings' command becomes `SIMCTL_CHILD_grpc_proxy=http://127.0.0.1:<port>
   SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH=<data dir>/proxy-ca.pem
   xcrun simctl launch booted <bundle id>`, the same two variables in an
   Xcode scheme. Nothing on the simulator changes and nothing has to be
   reset after "Reset proxy CA". M8.8 becomes: the command in the
   settings, an iOS sample button making a TLS gRPC-Core call, and
   `make e2e-ios` with the proxy on. Cons: drops D.1's button (a planned
   feature); the sample gains a gRPC-Core dependency (`grpc/grpc-ios`
   through SwiftPM, products `gRPC-Core` and `gRPC-cpp`, Apache-2.0, a
   C++ build whose time is not measured yet) in Debug.
2. **Keep the button as ADR-0101 wrote it, and prove the feed in
   plaintext.** The button is built and tested against a fake `simctl`;
   the sample's gRPC-Core call goes to a plaintext origin, so the CA is
   not on the path the end to end checks. Cons: the button serves no
   client B.1 feeds; a developer installing it still sees TLS gRPC fail
   at the proxy's leaf.
3. **Both: the variable in the command and the button kept** for clients
   an app points at the proxy itself (`URLSession` with a
   `connectionProxyDictionary`, Network.framework's
   `ProxyConfiguration`), which do read the keychain. Cons: a feature for
   a path B.1 does not offer and nothing in the repo exercises.
4. **Leave the simulator out of M8.** M8.8 is dropped to a DEBT whose
   trigger is the first app with gRPC-Core traffic pointed at the proxy;
   the proxy stays proven by curl and grpc-java on the Mac
   (`:desktop:proxy:test`). Cons: layer 4 is never shown from a
   simulator in this milestone.

Recommended: 1 — it is the only option whose end to end goes through the
CA the way an app would, and it removes a step (no keychain install, no
keychain reset) instead of adding one.

## Decision

Empty while `Proposed`. Accepting 1 or 4 supersedes ADR-0101 D.1 and
rewrites M8.8; 2 and 3 keep it.

## Consequences

To be written with the decision.
