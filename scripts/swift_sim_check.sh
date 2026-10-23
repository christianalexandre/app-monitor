#!/usr/bin/env bash
# iOS simulator tests of sdk/ios (ADR-0047): generates sdk/ios/TestHost with
# XcodeGen and runs its hosted tests with xcodebuild on an iPhone simulator.
# SIM_DEVICE picks the simulator by name; default the first available iPhone.
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
