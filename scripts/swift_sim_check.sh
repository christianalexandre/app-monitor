#!/usr/bin/env bash
# iOS simulator tests of sdk/ios (ADR-0047): generates sdk/ios/TestHost, runs its
# hosted tests on an iPhone simulator (SIM_DEVICE, default the first available),
# then checks the local network keys are in the Debug app only (ADR-0049).
set -euo pipefail
cd "$(dirname "$0")/.."

tag=swift-sim
source scripts/ios_lib.sh
device="$(ios_device)"

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
  check_network_keys "$config" "$products/$config-iphonesimulator/TestHost.app"
done
echo "swift-sim: local network keys in the Debug app only"
