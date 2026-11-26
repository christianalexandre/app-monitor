# iOS

Four steps, all needed on a simulator-only app too. Release builds hold none
of the SDK's code: it is not linked and has no stubs
([ADR-0155](../adr/0155-ios-sdk-left-out-of-release-builds-by-the-app.md)).

## 1. Add the package

*File → Add Package Dependencies…*,
`https://github.com/christianalexandre/app-monitor`, *Dependency Rule* *Up to
Next Major Version* from `1.0.0`, product `AppMonitor`, to the app target. From
`1.0.0`, only a major version changes the API.

In XcodeGen:

```yaml
packages:
  AppMonitor:
    url: https://github.com/christianalexandre/app-monitor
    majorVersion: 1.0.0
```

## 2. Start it under `#if DEBUG`

In `App.init` or `application(_:didFinishLaunchingWithOptions:)`:

```swift
#if DEBUG
import AppMonitor
#endif

#if DEBUG
AppMonitor.start(AppMonitorOptions(appName: "My App"))
#endif
```

## 3. Exclude it from every release configuration

In the app target's *Build Settings*, *Excluded Source File Names* for
`Release` and every other configuration that ships (`Prod`, `Staging`, …):

```text
EXCLUDED_SOURCE_FILE_NAMES = AppMonitor*
```

In an `.xcconfig`, `EXCLUDED_SOURCE_FILE_NAMES[config=Release] = AppMonitor*`;
in XcodeGen, under the target's `settings: configs: Release:`. A
configuration's name does not matter, only this setting does.

## 4. Paste the Run Script

Add a *New Run Script Phase* to the app target, as the last phase, and paste
the script below. It adds the local network keys a physical device needs,
and **fails a build that would ship the SDK**: an optimized configuration
without step 3. There is nothing to fill in. An `APPMONITOR_LOCAL_NETWORK_USAGE`
build setting changes the usage text, and a configuration that must keep the
SDK optimized sets `APPMONITOR_ALLOW_OPTIMIZED = YES`.

```sh appmonitor-run-script
#!/bin/sh
# AppMonitor Run Script (ADR-0155). A configuration whose EXCLUDED_SOURCE_FILE_NAMES
# excludes AppMonitor.o is left as is; any other links the SDK: it fails when
# optimized, else gets the local network keys. Keys the app already has are kept.
set -eu

plist="${TARGET_BUILD_DIR:?run from an Xcode Run Script build phase}/${INFOPLIST_PATH:?}"
usage="${APPMONITOR_LOCAL_NETWORK_USAGE:-AppMonitor sends the network traffic of this debug build to the AppMonitor desktop app on your local network.}"
service="_appmonitor._tcp"

set -f
for pattern in ${EXCLUDED_SOURCE_FILE_NAMES:-}; do
  case AppMonitor.o in
    $pattern)
      echo "AppMonitor: excluded from ${CONFIGURATION:-this build}, Info.plist left as is"
      exit 0
      ;;
  esac
done
set +f

if [ "${SWIFT_OPTIMIZATION_LEVEL:--Onone}" != "-Onone" ] && [ "${APPMONITOR_ALLOW_OPTIMIZED:-NO}" != YES ]; then
  echo "error: AppMonitor is linked into the optimized ${CONFIGURATION:-build}." \
    "Set EXCLUDED_SOURCE_FILE_NAMES = AppMonitor* on it, or APPMONITOR_ALLOW_OPTIMIZED = YES to keep the SDK."
  exit 1
fi

if ! plutil -extract NSLocalNetworkUsageDescription raw -o /dev/null "$plist" 2>/dev/null; then
  plutil -insert NSLocalNetworkUsageDescription -string "$usage" "$plist"
fi
if ! plutil -extract NSBonjourServices raw -o /dev/null "$plist" 2>/dev/null; then
  plutil -insert NSBonjourServices -array "$plist"
fi
if ! plutil -extract NSBonjourServices json -o - "$plist" | grep -q "\"$service\""; then
  plutil -insert NSBonjourServices -string "$service" -append "$plist"
fi
echo "AppMonitor: local network keys in $plist"
```

The phase's files depend on the target's `ENABLE_USER_SCRIPT_SANDBOXING`
build setting:

- **Sandboxing on** (the default in new Xcode projects): declare the
  `Info.plist` as input and output, or the sandbox denies the write:

  ```text
  Input files:   $(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)
  Output files:  $(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)
                 $(DERIVED_FILE_DIR)/appmonitor-local-network-keys.stamp
  ```

- **Sandboxing off:** the stamp is the only output. With the `Info.plist`
  as an output too, Xcode stops with `Multiple commands produce
  '…/Info.plist'`.

  ```text
  Input files:   $(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)
  Output files:  $(DERIVED_FILE_DIR)/appmonitor-local-network-keys.stamp
  ```

If the app already has a script phase that edits the built `Info.plist`
(a build number, for one) and declares it as an output, Xcode stops with
`invalid task … with mutable output but no other virtual output node` or
`Multiple commands produce '…/Info.plist'`. Give that phase an output of
its own beside the `Info.plist`, a stamp such as
`$(DERIVED_FILE_DIR)/build-number.stamp`.

On the simulator the desktop is reached on `127.0.0.1` without the local
network keys, but the Run Script is still the one check that fails a
configuration which ships the SDK because step 3 was forgotten.

## What is captured

Every `URLSession`, with no further code: requests, responses, errors,
background sessions and WebSocket tasks. A request carrying
`X-AppMonitor-Capture` is skipped: `sdk/ktor` sets that header on what it has
already captured
([ADR-0064](../adr/0064-ktor-swift-dedupe-by-correlation-header.md)).

A socket the app's other code opens, a gRPC-Core SDK's for one, shows as a
connection: host, port, bytes and timing, no content.
`captureConnections: false` in the options turns that off
([SDD 07](../sdd/07_ios_urlsession_capture.md)).

A working app is in [`samples/ios`](../../samples/ios).

## gRPC Swift 2

Add the `AppMonitorGRPC` product too, from iOS 18; `AppMonitor*` in step 3
excludes it as well. Then pass its interceptor to the client:

```swift
#if DEBUG
import AppMonitorGRPC
let interceptors: [any ClientInterceptor] = [AppMonitorGRPCInterceptor()]
#else
let interceptors: [any ClientInterceptor] = []
#endif

let client = GRPCClient(transport: transport, interceptors: interceptors)
```

Each call shows as one exchange, with messages as bytes
([ADR-0099](../adr/0099-grpc-swift-adapter.md)). gRPC Swift 1 is not
covered.

## Nothing in the desktop?

The SDK logs why once, in Xcode's console (subsystem `AppMonitor`):

- on a device, the Run Script's keys are missing from `Info.plist`, or local
  network access was denied in *Settings → Privacy & Security → Local
  Network*;
- anywhere, it cannot reach a desktop, or the desktop has not approved it.

Console.app shows the same lines without Xcode.
`AppMonitorOptions(host: "192.168.1.20")` skips discovery. See also
[physical devices](desktop.md#physical-devices).
