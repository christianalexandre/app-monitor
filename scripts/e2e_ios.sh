#!/usr/bin/env bash
# iOS end to end (ADR-0057): the sample's Debug app on an iPhone simulator against
# the headless server, every expected envelope asserted in the server log.
# SIM_DEVICE picks the simulator (booted first), E2E_TIMEOUT (s, default 120) bounds the wait.
set -euo pipefail
cd "$(dirname "$0")/.."

out=build/e2e-ios
server_log=$out/server.log
timeout="${E2E_TIMEOUT:-120}"
app=dev.appmonitor.sample
tag=e2e-ios
source scripts/e2e_lib.sh

command -v xcrun >/dev/null 2>&1 || fail "xcrun not found (needs macOS with Xcode; ADR-0047)"
# Prints "<udid> <name>" of the simulators `simctl list` shows in state $1, iPhones only.
simulators() {
  xcrun simctl list devices "$1" | sed -n 's/^ *\(iPhone[^(]*\) (\([0-9A-F-]*\)) .*/\2 \1/p' | sed 's/ *$//'
}
pick="$(simulators booted | awk -v d="${SIM_DEVICE:-}" 'd == "" || substr($0, 38) == d' | head -1)"
if [[ -z "$pick" ]]; then
  pick="$(simulators available | awk -v d="${SIM_DEVICE:-}" 'd == "" || substr($0, 38) == d' | head -1)"
  [[ -n "$pick" ]] || fail "no iPhone simulator${SIM_DEVICE:+ named $SIM_DEVICE} (Xcode > Settings > Components)"
  xcrun simctl boot "${pick%% *}"
fi
udid="${pick%% *}"
xcrun simctl bootstatus "$udid" -b >/dev/null

SIM_DEVICE="${pick#* }" scripts/ios_sample_check.sh
./gradlew -q :desktop:server:installDist
mkdir -p "$out"
started="$(date '+%Y-%m-%d %H:%M:%S')"
trap 'kill "${server_pid:-}" 2>/dev/null || true
  xcrun simctl spawn "$udid" log show --style compact --start "$started" \
    --predicate "process == \"Sample\"" >"$out/simulator.log" 2>/dev/null || true' EXIT
start_server

xcrun simctl terminate "$udid" "$app" 2>/dev/null || true
xcrun simctl install "$udid" build/ios-sample/Build/Products/Debug-iphonesimulator/Sample.app
xcrun simctl launch "$udid" "$app" -host 127.0.0.1 -port "$port" -runAll YES >/dev/null
echo "e2e-ios: sample on ${pick#* } ($udid), server on port $port"

# One expectation per line, as `missing` in scripts/e2e_lib.sh reads them.
expected="GET https://httpbin.org/json ResponseReceived
POST https://httpbin.org/post ResponseReceived
GET https://httpbin.org/status/404 ResponseReceived
GET https://httpbin.org/delay/10 RequestFailed
PUT https://httpbin.org/put ResponseReceived
GET https://echo.websocket.org ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE
GET https://httpbin.org/bytes/4096 ResponseReceived"

assert_envelopes "$app" "$expected" "$timeout" "simulator log in $out/simulator.log"
