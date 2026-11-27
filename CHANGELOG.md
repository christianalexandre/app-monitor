# Changelog

Every SDK ships the same version at the same `X.Y.Z` tag (ADR-0054,
ADR-0216). The desktop app has its own version, at `desktop-X.Y.Z` tags, on
the SDKs' major (ADR-0232); its sections are headed `Desktop X.Y.Z`. The
publish workflow uses a version's section as its GitHub Release notes
(ADR-0217).

## Desktop 1.1.1

A fix release of the desktop app, for the `1.0.0` SDKs. The SDKs are unchanged
and stay at `1.0.0`.

- **Clicking the update notice no longer closes the window** where no browser
  can be opened, such as Linux without `xdg-open`.
- **The guides are a website** at
  <https://christianalexandre.github.io/app-monitor/>, built from the README
  and `docs/guide/`.

## Desktop 1.1.0

The desktop app's first release under its own version (ADR-0232), for the
`1.0.0` SDKs. The SDKs are unchanged and stay at `1.0.0`.

- **The packages start.** `1.0.0`'s packages failed at launch on every OS;
  the runtime image now holds every JDK module the app uses (ADR-0230).
- **Install it with a package manager or a package** attached to this
  release:
  - `brew install --cask christianalexandre/tap/app-monitor`
  - `scoop bucket add christianalexandre https://github.com/christianalexandre/homebrew-tap`
    then `scoop install christianalexandre/app-monitor`
  - `.deb` and `.rpm` on Linux

  The packages are not signed by Apple or Microsoft; the first launch asks
  once ([desktop guide](docs/guide/desktop.md)).
- **The update notice follows desktop releases only.** It reads the newest
  `desktop-X.Y.Z` release and skips the SDKs' ones.

Known limits:

- The packages are for macOS on Apple silicon, Windows x64 and Linux x64;
  elsewhere, run it from the repository with JDK 21.

## 1.0.0

The first stable version: the SDKs' API changes only with a major version from
here on. The libraries are the same code as `0.1.0`; the desktop app is
published for the first time.

- **The desktop app, installed with a package manager or a package.**
  Homebrew on macOS (Apple silicon), Scoop on Windows, `.deb` and `.rpm` on
  Linux, all attached to this release:
  - `brew install --cask christianalexandre/tap/app-monitor`
  - `scoop bucket add christianalexandre https://github.com/christianalexandre/homebrew-tap`
    then `scoop install christianalexandre/app-monitor`

  The packages are not signed by Apple or Microsoft; the first launch asks
  once ([desktop guide](docs/guide/desktop.md)).
- **A newer version, noticed.** The desktop reads this repository's latest
  release at launch and once a day, and shows a newer one in its status bar.
  *Settings → Updates* turns it off; the request carries only the desktop's
  version.
- **The desktop, after the `0.1.0` field test:** the newest request on top;
  the monitor paused and resumed from the title bar; mocking switched off and
  on for every device; a long JSON value opened and folded with a click; the
  network announcement following a laptop that sleeps or changes network.
- **Gradle plugin and SwiftPM:** `1.0.0` in the guides. On iOS, *Up to Next
  Major Version* from `1.0.0`.

Known limits:

- On Android and the JVM, a consumer needs Kotlin 2.2 or newer; a KMP module
  that reads `appmonitor-ktor`'s iOS klibs needs Kotlin 2.4 or newer
  (ADR-0218).
- The desktop's packages are for macOS on Apple silicon, Windows x64 and
  Linux x64; elsewhere, run it from the repository with JDK 21.

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
