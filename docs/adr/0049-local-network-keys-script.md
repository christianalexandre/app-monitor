---
id: ADR-0049
title: Local network keys added to the built Info.plist by a Run Script phase
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0049 — Local network keys added to the built Info.plist by a Run Script phase

## Context

ADR-0036 E.1 decided the keys (`NSLocalNetworkUsageDescription`,
`NSBonjourServices = [_appmonitor._tcp]`) go into debug builds only, through
a build-phase script; PLAN-0003 M2.11 ships it. Left open: which
configurations count as debug, what happens to keys the app already has, and
how the phase runs under Xcode's user script sandboxing.

Evidence, checked on 2026-10-23 on this machine (Xcode 26.6, XcodeGen
2.46.0, iOS simulator SDK), on the `TestHost` app of ADR-0047:

- The package compiles the SDK when the app's configuration name contains
  `Debug`, whatever its settings (ADR-0048).
- In the build log, `ProcessInfoPlistFile` writes the built `Info.plist`,
  then the Run Script phase runs, then `CodeSign`; `codesign -v` passes on
  the edited app. A second build runs neither again and the keys stay.
- With `ENABLE_USER_SCRIPT_SANDBOXING = YES` (Xcode's default for new
  projects), writing the built `Info.plist` is denied (`Sandbox: plutil deny
  file-write-data`) unless it is a declared output; declared as the only
  output, the build fails with "mutable output but no other virtual output
  node". Declared as input and output, plus a second output in
  `$(DERIVED_FILE_DIR)` the script never writes, the build passes.
- `plutil -insert` keeps the file binary and takes the value as one
  argument; `PlistBuddy -c "Add … string $value"` parses the value inside
  its command string, so quotes in it break the command.
- The previous tool's README script compares `CONFIGURATION` with one name
  the developer types in, and deletes then re-adds both keys.

## Options

1. **`sdk/ios/scripts/add_local_network_keys.sh`, run from a Run Script
   phase after the app's other phases**: acts when `CONFIGURATION` contains
   `Debug`, the same rule as the SDK's `DEBUG`; adds
   `NSLocalNetworkUsageDescription` only when absent (default text, or
   `APPMONITOR_LOCAL_NETWORK_USAGE`), and appends `_appmonitor._tcp` to
   `NSBonjourServices` only when missing; uses `plutil`. The keys follow the
   SDK into exactly the builds that contain it, and an app that uses the
   local network itself keeps its own text and services.
2. **The previous tool's script**: the developer edits the configuration
   name, and an app's own keys are overwritten in debug builds.
3. **Per-configuration `INFOPLIST_KEY_` build settings**: no script, but
   `NSBonjourServices` is an array, which those settings cannot express.

## Decision

Option 1. With sandboxing on, the phase declares the script and
`$(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)` as inputs, and that path plus
`$(DERIVED_FILE_DIR)/appmonitor-local-network-keys.stamp` as outputs, as
`sdk/ios/TestHost/project.yml` does. `scripts/test_add_local_network_keys.sh`
tests the script on plists; `make swift-sim` checks the Debug `TestHost.app`
has both keys and a Release build of it has none.

## Consequences

- An app's configuration whose name lacks `Debug` gets neither the SDK nor
  the keys: never a permission prompt for a tool the build does not contain.
- The script never removes a key, so an app's own release keys survive.
- `make swift-sim` grows by one Release build of `TestHost`, 0.9–2.2 s on
  this Mac (whole run 9.7 s); CI is unchanged (ADR-0037).
- Where an app finds the script depends on how the package is consumed,
  decided with distribution in M10; M2.12's sample calls it by relative path.
