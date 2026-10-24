#!/usr/bin/env bash
# Builds samples/ios (ADR-0050) in Debug and Release for an iPhone simulator
# (SIM_DEVICE, default the first available); the local network keys must be in the
# Debug app only (ADR-0049), the Release app must hold no AppMonitor type but the stubs.
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

# Prints the names the AppMonitor module declares in the binaries of one built app.
sdk_names() {
  find "$1" -maxdepth 1 -type f \( -name Sample -o -name '*.dylib' \) -exec nm {} + 2>/dev/null |
    awk '{print $NF}' | xcrun swift-demangle --compact |
    grep -oE '(^|[^A-Za-z_.])AppMonitor\.[A-Za-z0-9_]+' | sed 's/.*AppMonitor\.//' | sort -u
}

debug_names="$(sdk_names "$products/Debug-iphonesimulator/Sample.app")"
if ! grep -qx MonitorClient <<<"$debug_names"; then
  echo "ios-sample: no MonitorClient in the Debug app; the symbol check sees nothing" >&2
  exit 1
fi
release_names="$(sdk_names "$products/Release-iphonesimulator/Sample.app" | tr '\n' ' ')"
if [[ "$release_names" != "AppMonitor AppMonitorOptions " ]]; then
  echo "ios-sample: Release app holds AppMonitor names other than the stubs: $release_names(ADR-0048)" >&2
  exit 1
fi
echo "ios-sample: Debug and Release build on $device; keys and SDK in Debug only"
