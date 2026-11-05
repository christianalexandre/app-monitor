#!/usr/bin/env bash
# The iOS suites repeated, as a hosted runner's stalls need (ADR-0201): STRESS_RUNS rounds of the
# package's and TestHost's suites idle, then under STRESS_LOAD busy loops, then `swift_sim_check.sh`
# on a simulator made for it. Exits 1 if any round failed; the failed rounds' logs stay in build/swift-stress.
set -euo pipefail
cd "$(dirname "$0")/.."

tag=swift-stress
source scripts/ios_lib.sh
runs="${STRESS_RUNS:-20}"
load="${STRESS_LOAD:-100}"
device="$(ios_device)"
destination="platform=iOS Simulator,name=$device${SIM_OS:+,OS=$SIM_OS}"
out="$PWD/build/swift-stress"
rm -rf "$out"
mkdir -p "$out"

busy=()
simulator=""
cleanup() {
  ((${#busy[@]})) && kill "${busy[@]}" 2>/dev/null || true
  if [[ -n "$simulator" ]]; then
    xcrun simctl shutdown "$simulator" 2>/dev/null || true
    xcrun simctl delete "$simulator" 2>/dev/null || true
  fi
}
trap cleanup EXIT

package=(env -C sdk/ios xcodebuild -scheme AppMonitor-Package -destination "$destination" -derivedDataPath "$out/package")
xcodegen generate --quiet --spec sdk/ios/TestHost/project.yml
host=(xcodebuild -project sdk/ios/TestHost/TestHost.xcodeproj -scheme TestHost -destination "$destination" -derivedDataPath "$out/host")
"${package[@]}" build-for-testing >"$out/build.log" 2>&1 && "${host[@]}" build-for-testing >>"$out/build.log" 2>&1 \
  || ios_fail "build failed, log in $out/build.log"
rm "$out/build.log"

failed=0
# $1 what ran; the rest, its xcodebuild without the action.
rounds() {
  local name="$1" passed=0 log i
  shift
  for ((i = 1; i <= runs; i++)); do
    log="$out/$name-$i.log"
    if "$@" test-without-building >"$log" 2>&1; then
      passed=$((passed + 1))
      rm "$log"
    else
      failed=$((failed + 1))
      grep -E '^. Test .*recorded an issue' "$log" >&2 || true
    fi
  done
  echo "$tag: $name: $passed of $runs passed"
}

rounds package-idle "${package[@]}"
rounds host-idle "${host[@]}"
for ((i = 0; i < load; i++)); do
  yes >/dev/null &
  busy+=($!)
done
rounds package-load "${package[@]}"
rounds host-load "${host[@]}"
{
  kill "${busy[@]}"
  wait "${busy[@]}"
} 2>/dev/null || true
busy=()

simulator="$(xcrun simctl create "iPhone Stress" "$device")"
if SIM_DEVICE="iPhone Stress" SIM_OS="" scripts/swift_sim_check.sh >"$out/new-simulator.log" 2>&1; then
  echo "$tag: new simulator: passed"
  rm "$out/new-simulator.log"
else
  failed=$((failed + 1))
  echo "$tag: new simulator: failed, log in $out/new-simulator.log" >&2
fi

((failed == 0)) || ios_fail "$failed failed, logs in $out"
