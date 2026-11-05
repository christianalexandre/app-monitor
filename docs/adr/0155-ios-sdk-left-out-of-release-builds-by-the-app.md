---
id: ADR-0155
title: The iOS SDK is left out of release builds by the app, guarded by a Run Script pasted from the README
status: Accepted
date: 2026-10-28
superseded_by:
---

# ADR-0155 — The iOS SDK is left out of release builds by the app, guarded by a Run Script pasted from the README

## Context

The first field test, in a KMP app whose Xcode configurations are `Dev`
(debug settings) and `Prod` (release settings), showed no iOS traffic.
`AppMonitor.start` ran the release stubs of ADR-0048 in `Dev` and said
nothing. The app also pointed its Run Script at
`${BUILD_DIR%/Build/*}/SourcePackages/checkouts/app-monitor/sdk/ios/scripts/add_local_network_keys.sh`
and forced `CONFIGURATION=Debug`, because the README showed a script path
inside a local checkout (ADR-0049).

Measured on 2026-10-28 (Xcode 26.6, XcodeGen; probes in a local package,
`xcodebuild -configuration <name>`, binaries read with `nm`, `strings`,
`otool -L`):

1. **Xcode builds a package as debug by the configuration's name alone.**
   The name must contain `debug` or `development`, in any case. `Debug`,
   `DevDebug`, `dev-debug` and `Development` build it as debug, and so do
   `ReleaseDebug` and `ProdDevelopment`, which have release settings.
   `Dev`, `Develop`, `QA`, `Beta`, `Staging` and `Prod` build it as release.
   So does `Dev` with `DEBUG` in the project's
   `SWIFT_ACTIVE_COMPILATION_CONDITIONS`: the settings never reach the
   package. The Swift forums report the same rule, and SwiftPM's request
   for a setting to override it (SR-16060,
   swiftlang/swift-package-manager#9715) is open.
2. **`#if DEBUG` around the app's `import` does not drop the package.**
   Xcode links each package target as an object file (`AppMonitor.o`), so a
   `Prod` binary that never imports it still holds `MonitorHolder`, the
   `rebind` hooks, the `_appmonitor._tcp` string and 15,615 SwiftProtobuf
   symbols. A `.static` product changes nothing.
3. **`EXCLUDED_SOURCE_FILE_NAMES` on a configuration leaves the product
   out.** With the automatic `AppMonitor` product, `AppMonitor*` (or
   `AppMonitor.o`; bare `AppMonitor` and `AppMonitor.framework` do not
   match) gives an iOS `Prod` app with no `MonitorHolder`, no `rebind`
   hooks and no `_appmonitor._tcp`. SwiftProtobuf stays linked (14,774
   symbols): it is its own package target. `Dev` holds the real SDK in the
   app's debug dylib. A `Prod` that imports the module outside `#if DEBUG`
   fails to build (`unable to resolve module dependency`).
4. **A `.dynamic` product takes SwiftProtobuf out too, but adds a step.**
   `AppMonitor*` then leaves `Prod` with no framework and 0 SwiftProtobuf
   symbols. But an Xcode project that adds the product the way the UI
   writes it (the *Frameworks* phase only, as `samples/ios`'s generated
   `.pbxproj` does) links `@rpath/AppMonitor.framework` without embedding
   it, so the Debug app cannot launch until someone picks *Embed & Sign*.

The owner (2026-10-28): SDK code must never reach a production build,
because present code can be switched on (Frida, for one), so a runtime
switch is out. The setup must be plug and play. The local network keys
script sits in the README for the developer to paste, as in
`app-network-monitor`. CocoaPods (`:configurations => ['Debug']`) is out:
it is deprecated.

## Options

1. **Stubs under the package's `#if DEBUG` (ADR-0048), keys by name
   (ADR-0049).** Follows fact 1. A configuration such as `Dev` silently
   gets no SDK, and one such as `ReleaseDebug` silently ships it.
2. **Real code always, switched on at run time.** Rejected by the owner.
3. **The app leaves the SDK out.** The `AppMonitor` product stays
   automatic and holds no `#if DEBUG`. The app starts it under its own `#if DEBUG`,
   which follows the app's settings. It sets `EXCLUDED_SOURCE_FILE_NAMES
   = AppMonitor*` on each configuration that ships. The README's Run
   Script reads the same settings: an excluded configuration is left as
   is; any other links the SDK and **fails the build when optimized**
   (`SWIFT_OPTIMIZATION_LEVEL` not `-Onone`, unless
   `APPMONITOR_ALLOW_OPTIMIZED = YES`), else gets the local network keys.
4. **The same with a `.dynamic` product** (fact 4). SwiftProtobuf would
   leave release builds too, but one more step that crashes the Debug app
   when forgotten.
5. **A command plugin that writes the setting into the app's
   `.pbxproj`.** It saves one manual line, but it edits other people's
   project files. Not tried, left for later.

## Decision

Option 3. The app sets `EXCLUDED_SOURCE_FILE_NAMES` by hand: no SwiftPM
mechanism changes an app's build settings. Forgetting it fails the first
optimized build. The Run Script matches the patterns against
`AppMonitor.o`, the name Xcode compares.

## Consequences

- No configuration name rule anymore. `Dev` works, and a release under
  any name ships nothing once excluded. Without the Run Script, an
  optimized build that forgot the exclusion is not caught: the README
  makes the script one of the four steps.
- The release stubs and their API comparison are gone. One public API
  dump stays (`scripts/swift_api_check.sh`). `swift_proto_gen.sh` no
  longer wraps the generated code. `AppMonitorGRPC` stays an automatic
  product and `AppMonitor*` excludes it as well.
- `make ios-sample` and `make kmp-sample` check that the Release app holds
  no SDK name, embedded frameworks scanned too; `make swift-sim` that its
  Release TestHost has no keys. DEBT-0040 stays open: SwiftProtobuf, not
  SDK code, is still linked in release; option 4 is its way out.
- A SwiftPM command-line consumer (`make publish-dry-run`) has no
  exclusion, so its release build links the real SDK. Apps build with
  Xcode.
- Supersedes ADR-0048 and ADR-0049.
