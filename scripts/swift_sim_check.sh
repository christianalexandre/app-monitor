#!/usr/bin/env bash
# iOS simulator tests of sdk/ios (ADR-0047, ADR-0170): the package's suite, then the hosted
# tests of sdk/ios/TestHost, on an iPhone simulator (SIM_DEVICE, default the first available;
# SIM_OS picks its runtime, e.g. 16.0), then checks the local network keys are in the Debug
# app only (ADR-0049).
set -euo pipefail
cd "$(dirname "$0")/.."

tag=swift-sim
source scripts/ios_lib.sh
device="$(ios_device)"
destination="platform=iOS Simulator,name=$device${SIM_OS:+,OS=$SIM_OS}"
on="$device${SIM_OS:+ (iOS $SIM_OS)}"
mkdir -p build
log=build/ios-simulator.log

# $1 what ran; the log's last Swift Testing summary, else its failures.
run_tests() {
  if "${@:2}" >>"$log" 2>&1; then
    echo "swift-sim: $1: $(grep -E '^. Test run' "$log" | tail -1) on $on"
  else
    grep -E '^. Test .*(issue|failed)|error:' "$log" >&2 || true
    echo "swift-sim: $1 failed on $on, full log in $log" >&2
    exit 1
  fi
}

: >"$log"
run_tests "package" \
  env -C sdk/ios xcodebuild test -scheme AppMonitor-Package -destination "$destination" \
  -derivedDataPath ../../build/ios-simulator-package

xcodegen generate --quiet --spec sdk/ios/TestHost/project.yml
run_tests "TestHost" \
  xcodebuild test -project sdk/ios/TestHost/TestHost.xcodeproj -scheme TestHost \
  -destination "$destination" -derivedDataPath build/ios-simulator

products=build/ios-simulator/Build/Products
if ! xcodebuild build \
  -project sdk/ios/TestHost/TestHost.xcodeproj -scheme TestHost -configuration Release \
  -destination "$destination" \
  -derivedDataPath build/ios-simulator >>"$log" 2>&1; then
  grep -E 'error:' "$log" >&2 || true
  echo "swift-sim: Release build failed, full log in $log" >&2
  exit 1
fi
for config in Debug Release; do
  check_network_keys "$config" "$products/$config-iphonesimulator/TestHost.app"
done
echo "swift-sim: local network keys in the Debug app only"
