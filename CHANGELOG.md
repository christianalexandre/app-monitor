# Changelog

Every SDK ships the same version at the same `X.Y.Z` tag (ADR-0054,
ADR-0216). The publish workflow uses a version's section as its GitHub
Release notes (ADR-0217).

## 0.1.0

The first published version, for testing in real apps. The API may still
change before `1.0.0`.

- **Android:** the Gradle plugin `io.github.christianalexandre.appmonitor`
  on Maven Central. Applied alone, it adds `appmonitor-okhttp` to debug
  builds and its no-op to release builds, and captures every OkHttp call
  and WebSocket.
- **iOS:** the `AppMonitor` Swift package at this tag, from iOS 15.
  It captures all `URLSession` traffic, background sessions included. The
  `AppMonitorGRPC` product captures gRPC Swift 2 calls, from iOS 18.
- **Kotlin Multiplatform:** `appmonitor-ktor` for Ktor 3.6+ on Android,
  `iosArm64` and `iosSimulatorArm64`. With Ktor's OkHttp and Darwin
  engines, the Android and iOS steps are enough.
- **Mocks, breakpoints and replay** from the desktop on every platform.
- Every artifact has a no-op twin with the same API, and release builds
  hold no SDK code.

Known limits:

- On Android and the JVM, a consumer needs Kotlin 2.2 or newer. A KMP
  module that reads `appmonitor-ktor`'s iOS klibs (another engine than
  Darwin) needs Kotlin 2.4 or newer (ADR-0218).
- The README's *Installation* still shows the steps from before publishing.
  It is rewritten after this release.
