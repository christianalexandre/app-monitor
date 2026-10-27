---
id: ADR-0109
title: gRPC-Core in the iOS sample and the proxy in the iOS end to end
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0109 — gRPC-Core in the iOS sample and the proxy in the iOS end to end

## Context

ADR-0108 option 1 makes M8.8 an iOS sample button whose TLS gRPC-Core
call goes through the desktop's proxy, trusted through
`GRPC_DEFAULT_SSL_ROOTS_FILE_PATH`, and checked by `make e2e-ios`. It left
three things to this task: the check that a simulator app reads a file on
the Mac, how the sample takes `grpc/grpc-ios`, and its build time.

Evidence, on this machine (Xcode 26.5, iPhone 17 Pro simulator, iOS 26.5):

- **The file is read.** The sample launched with
  `SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH` pointing at a file under
  the Mac's home: a copy of `/etc/ssl/cert.pem` → the call to
  `grpcb.in:9001` returns status 0; a self-signed certificate alone →
  status 14, `CERTIFICATE_VERIFY_FAILED: unable to get local issuer
  certificate`. Then `make e2e-ios` passed with the proxy's
  `proxy-ca.pem` as the roots.
- **The package.** `grpc/grpc-ios` 1.84.0 (latest release, 2026-09-14,
  `gh api repos/grpc/grpc-ios/releases/latest`), Apache-2.0, product
  `gRPC-Core`, depending on `firebase/abseil-cpp-SwiftPM` and
  `firebase/boringssl-SwiftPM`. Its public headers are
  `spm-core-include/grpc`, a symlink to `include/grpc`; SwiftPM's
  module map is an umbrella over that directory, and `import gRPC_Core`
  finds no symbol (`cannot find 'grpc_init' in scope`). A bridging
  header including `<grpc/grpc.h>` and its siblings builds.
- **Build time.** Cold builds of `samples/ios` for the simulator, with and
  without the package: Debug 254 s against 12 s, Release 128 s against
  18 s. The Release binary grows from 3.5 MB to 21.7 MB and keeps 67
  `grpc_` symbols: Xcode links a package product in every configuration
  and gRPC-Core's static initializers keep part of it past dead
  stripping.
- **Where Xcode writes the pins.** With `sdk/ios` as the sample's local
  package, each sample build rewrote the tracked
  `sdk/ios/Package.resolved` with gRPC-Core's three pins, and
  `swift package resolve --package-path sdk/ios` pruned them again: the
  tree was dirty after every sample build. With the root `Package.swift`
  (the manifest apps resolve, ADR-0054) as the local package, `make
  ios-sample` leaves no file changed.
- **The proxy's upstream trust.** The proxy verifies origins against the
  JVM's trust store (SDD 11), and the end to end's origin is local
  (ADR-0074), so it needs a certificate the proxy trusts.

## Options

1. **The C API from Swift, the call in `#if DEBUG`; the headless server
   runs the proxy; the target serves gRPC over TLS.** The sample calls
   gRPC-Core's C API (no generated code, the message written as bytes)
   through a bridging header, and takes the SDK from the root
   `Package.swift`. `desktop/server` gains `--proxy DIR`,
   which runs `ProxyServer` on a free port with its CA in `DIR` and logs
   its exchanges as the `[proxy]` session. `tools/e2e-target` gains
   `--grpc-trust FILE`: a grpc-java origin on `127.0.0.1` with a
   self-signed certificate, `grpcbin.GRPCBin/DummyUnary` echoing as
   grpcb.in does, and a PKCS#12 trust store holding that certificate,
   handed to the server's JVM by `javax.net.ssl.trustStore`. Cons: the
   Release app carries what the linker keeps of gRPC-Core; the macOS
   gate jobs that build the sample (`samples`, `e2e-ios`) grow by
   minutes.
2. **gRPC-C++ through Objective-C++.** The generic stub of `gRPC-cpp`
   in a `.mm` file. Cons: a second, larger product to build, and the same
   linking in Release.
3. **A Debug-only framework target holding the call and the package.**
   Cons: Xcode still links the product into the framework in both
   configurations; keeping it out of Release needs per-configuration
   linker flags XcodeGen does not express for packages.

## Decision

Option 1. The sample's gRPC-Core is the app's own dependency, not SDK code,
so the "debug only" standing decision (ADR-0093) is not touched: the call
is compiled in Debug only, the package is linked in both, and
`scripts/ios_sample_check.sh` keeps checking AppMonitor names only. The
end to end always runs with the proxy; the server's `--proxy` serves
`make server` too.

## Consequences

- `make e2e-ios` checks layer 4 from a simulator: the call is stored by
  the proxy as one exchange with its request and response messages.
- `make samples` and `make e2e-ios` take about four more minutes cold on
  this machine; the gate's macOS jobs are watched by DEBT-0080.
- `desktop/server` depends on `desktop/proxy` (and through it on
  `desktop/storage`), which its tests and `sdk/core`'s JVM tests pull in.
