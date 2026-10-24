#!/usr/bin/env bash
# KMP end to end (ADR-0065, ADR-0075): samples/kmp's debug app on a booted emulator
# (`android`) or an iPhone simulator (`ios`) against the headless server, its Ktor calls to the
# local target (ADR-0074), every expected envelope asserted once; on iOS beside the Swift SDK.
# ANDROID_SERIAL and SIM_DEVICE pick the device, E2E_TIMEOUT (s, default 120) bounds the wait.
set -euo pipefail
cd "$(dirname "$0")/.."

platform="${1:-}"
tag=e2e-kmp-$platform
out=build/e2e-kmp-$platform
server_log=$out/server.log
target_log=$out/target.log
timeout="${E2E_TIMEOUT:-120}"
source scripts/e2e_lib.sh
[[ "$platform" == android || "$platform" == ios ]] || fail "usage: $0 android|ios"
mkdir -p "$out"

if [[ "$platform" == android ]]; then
  app=appmonitor.sample.kmp host=10.0.2.2
  boot_emulator
  ./gradlew -q :samples:kmp:androidApp:assembleDebug :desktop:server:installDist :tools:e2e-target:installDist
  trap 'kill "${server_pid:-}" "${target_pid:-}" 2>/dev/null || true; adb logcat -d >"$out/logcat.txt" 2>/dev/null || true' EXIT
  device_log="logcat in $out/logcat.txt"
else
  app=dev.appmonitor.kmpsample host=127.0.0.1
  boot_simulator
  build_ios_debug samples/kmp/iosApp/project.yml KmpSample build/kmp-sample
  ./gradlew -q :desktop:server:installDist :tools:e2e-target:installDist
  started="$(date '+%Y-%m-%d %H:%M:%S')"
  trap 'kill "${server_pid:-}" "${target_pid:-}" 2>/dev/null || true
    xcrun simctl spawn "$udid" log show --style compact --start "$started" \
      --predicate "process == \"KmpSample\"" >"$out/simulator.log" 2>/dev/null || true' EXIT
  device_log="simulator log in $out/simulator.log"
fi
start_server
start_target
target="http://$host:$target_port"

if [[ "$platform" == android ]]; then
  adb install -r samples/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk >/dev/null
  grant_local_network "$app"
  adb logcat -c
  adb shell am start -S -W --activity-clear-task -n "$app/.MainActivity" \
    --es host "$host" --ei port "$port" --es target "$target" --ez runAll true >/dev/null
  echo "$tag: sample on $ANDROID_SERIAL, server on port $port, target $target"
else
  xcrun simctl terminate "$udid" "$app" 2>/dev/null || true
  xcrun simctl install "$udid" build/kmp-sample/Build/Products/Debug-iphonesimulator/KmpSample.app
  xcrun simctl launch "$udid" "$app" -host "$host" -port "$port" -target "$target" -runAll YES >/dev/null
  echo "$tag: sample on $sim_name ($udid), server on port $port, target $target"
fi

# One expectation per line, as `missing` in scripts/e2e_lib.sh reads them.
expected="GET $target/json ResponseReceived
POST $target/post ResponseReceived
GET $target/status/404 ResponseReceived
GET $target/delay/10 RequestFailed
GET ws://$host:$target_port/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE"

assert_envelopes "$app" "$expected" "$timeout" "$device_log" "$host:$target_port"
if [[ "$platform" == ios ]]; then
  # The dedupe proves nothing unless the Swift SDK's session was there to capture twice (ADR-0064).
  sessions="$(grep -c "\] connected: .* $app " "$server_log" || true)"
  ((sessions >= 2)) || fail "$sessions session(s) for $app, the Swift SDK's and sdk/core's expected; server log in $server_log"
  echo "$tag: $sessions sessions, each exchange captured once"
fi
