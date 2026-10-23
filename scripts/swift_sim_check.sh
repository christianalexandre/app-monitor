#!/usr/bin/env bash
# iOS simulator tests of sdk/ios (ADR-0047): generates sdk/ios/TestHost, runs its
# hosted tests on an iPhone simulator (SIM_DEVICE, default the first available),
# then checks the local network keys are in the Debug app only (ADR-0049).
set -euo pipefail
cd "$(dirname "$0")/.."

for tool in xcodebuild xcrun xcodegen; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "swift-sim: $tool not found (needs macOS with Xcode and 'brew install xcodegen'; ADR-0047)" >&2
    exit 1
  fi
done

device="${SIM_DEVICE:-$(xcrun simctl list devices available | sed -n 's/^ *\(iPhone[^(]*\) (.*/\1/p' | head -1)}"
device="${device% }"
if [[ -z "$device" ]]; then
  echo "swift-sim: no available iPhone simulator (install one in Xcode > Settings > Components)" >&2
  exit 1
fi

xcodegen generate --quiet --spec sdk/ios/TestHost/project.yml
mkdir -p build
log=build/ios-simulator.log
if xcodebuild test \
  -project sdk/ios/TestHost/TestHost.xcodeproj -scheme TestHost \
  -destination "platform=iOS Simulator,name=$device" \
  -derivedDataPath build/ios-simulator >"$log" 2>&1; then
  echo "swift-sim: $(grep -E '^. Test run' "$log" | tail -1) on $device"
else
  grep -E '^. Test .*(issue|failed)|error:' "$log" >&2 || true
  echo "swift-sim: failed on $device, full log in $log" >&2
  exit 1
fi

products=build/ios-simulator/Build/Products
if ! xcodebuild build \
  -project sdk/ios/TestHost/TestHost.xcodeproj -scheme TestHost -configuration Release \
  -destination "platform=iOS Simulator,name=$device" \
  -derivedDataPath build/ios-simulator >>"$log" 2>&1; then
  grep -E 'error:' "$log" >&2 || true
  echo "swift-sim: Release build failed, full log in $log" >&2
  exit 1
fi
for config in Debug Release; do
  plist="$products/$config-iphonesimulator/TestHost.app/Info.plist"
  keys=0
  for key in NSLocalNetworkUsageDescription NSBonjourServices; do
    if plutil -extract "$key" raw -o /dev/null "$plist" 2>/dev/null; then keys=$((keys + 1)); fi
  done
  if [[ "$config" == Debug && $keys -ne 2 ]] || [[ "$config" == Release && $keys -ne 0 ]]; then
    plutil -p "$plist" >&2
    echo "swift-sim: $config TestHost.app has $keys of the 2 local network keys (ADR-0049)" >&2
    exit 1
  fi
done
echo "swift-sim: local network keys in the Debug app only"
