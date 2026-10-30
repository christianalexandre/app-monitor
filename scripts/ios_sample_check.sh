#!/usr/bin/env bash
# Builds samples/ios (ADR-0050) in Debug and Release for an iPhone simulator
# (SIM_DEVICE, default the first available); the local network keys must be in the
# Debug app only, the Release app must hold no AppMonitor name at all (ADR-0155).
set -euo pipefail
cd "$(dirname "$0")/.."

tag=ios-sample
source scripts/ios_lib.sh
device="$(ios_device)"

xcodegen generate --quiet --spec samples/ios/project.yml
mkdir -p build
log=build/ios-sample.log
: >"$log"
products=build/ios-sample/Build/Products

# Prints the names the AppMonitor module declares in the binaries of one built app.
sdk_names() {
  app_binaries "$1" | xargs nm 2>/dev/null |
    awk '{print $NF}' | xcrun swift-demangle --compact |
    grep -oE '(^|[^A-Za-z_.])AppMonitor\.[A-Za-z0-9_]+' | sed 's/.*AppMonitor\.//' | sort -u || true
}

# The C target of the connection layer (SDD 07) has no Swift names.
c_symbols() {
  app_binaries "$1" | xargs nm 2>/dev/null |
    grep -c appmonitor_connections_install || true
}

# The Run Script leaves any exclusion matching AppMonitor.o alone (ADR-0155): the bare object name must strip it too.
# Built first, so the Release app the checks below read is the project's own.
if ! xcodebuild build \
  -project samples/ios/Sample.xcodeproj -scheme Sample -configuration Release \
  -destination "platform=iOS Simulator,name=$device" \
  -derivedDataPath build/ios-sample EXCLUDED_SOURCE_FILE_NAMES=AppMonitor.o >>"$log" 2>&1; then
  grep -E 'error:' "$log" >&2 || true
  echo "ios-sample: Release build excluding AppMonitor.o failed, full log in $log" >&2
  exit 1
fi
check_network_keys Release "$products/Release-iphonesimulator/Sample.app"
only_object="$(sdk_names "$products/Release-iphonesimulator/Sample.app" | tr '\n' ' ')"
if [[ -n "$only_object" || "$(c_symbols "$products/Release-iphonesimulator/Sample.app")" != 0 ]]; then
  echo "ios-sample: Release app excluding AppMonitor.o still holds the SDK: $only_object(ADR-0155)" >&2
  exit 1
fi

for config in Debug Release; do
  if ! xcodebuild build \
    -project samples/ios/Sample.xcodeproj -scheme Sample -configuration "$config" \
    -destination "platform=iOS Simulator,name=$device" \
    -derivedDataPath build/ios-sample >>"$log" 2>&1; then
    grep -E 'error:' "$log" >&2 || true
    echo "ios-sample: $config build failed, full log in $log" >&2
    exit 1
  fi
  check_network_keys "$config" "$products/$config-iphonesimulator/Sample.app"
done

debug_names="$(sdk_names "$products/Debug-iphonesimulator/Sample.app")"
if ! grep -qx MonitorClient <<<"$debug_names"; then
  echo "ios-sample: no MonitorClient in the Debug app; the symbol check sees nothing" >&2
  exit 1
fi
release_names="$(sdk_names "$products/Release-iphonesimulator/Sample.app" | tr '\n' ' ')"
if [[ -n "$release_names" ]]; then
  echo "ios-sample: Release app holds AppMonitor names: $release_names(ADR-0155)" >&2
  exit 1
fi
if [[ "$(c_symbols "$products/Debug-iphonesimulator/Sample.app")" == 0 ]]; then
  echo "ios-sample: no appmonitor_connections_install in the Debug app; the C symbol check sees nothing" >&2
  exit 1
fi
if [[ "$(c_symbols "$products/Release-iphonesimulator/Sample.app")" != 0 ]]; then
  echo "ios-sample: Release app holds the connection layer's C code (ADR-0155)" >&2
  exit 1
fi
# DEBT-0040, ADR-0160: the SDK no longer links SwiftProtobuf, so neither app holds it.
for config in Debug Release; do
  protobuf="$(app_binaries "$products/$config-iphonesimulator/Sample.app" | xargs nm 2>/dev/null | grep -c SwiftProtobuf || true)"
  if [[ "$protobuf" != 0 ]]; then
    echo "ios-sample: $config app holds $protobuf SwiftProtobuf symbols (ADR-0160)" >&2
    exit 1
  fi
done
echo "ios-sample: Debug and Release build on $device; keys and SDK in Debug only, also excluding AppMonitor.o alone, no SwiftProtobuf"
