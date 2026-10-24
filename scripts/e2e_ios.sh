#!/usr/bin/env bash
# iOS end to end (ADR-0057): the sample's Debug app on an iPhone simulator against
# the headless server, its calls to the local target (ADR-0074), every expected envelope
# asserted in the server log.
# SIM_DEVICE picks the simulator (booted first), E2E_TIMEOUT (s, default 120) bounds the wait.
set -euo pipefail
cd "$(dirname "$0")/.."

out=build/e2e-ios
server_log=$out/server.log
target_log=$out/target.log
timeout="${E2E_TIMEOUT:-120}"
app=dev.appmonitor.sample
tag=e2e-ios
source scripts/e2e_lib.sh

mkdir -p "$out"
boot_simulator
build_ios_debug samples/ios/project.yml Sample build/ios-sample

./gradlew -q :desktop:server:installDist :tools:e2e-target:installDist
started="$(date '+%Y-%m-%d %H:%M:%S')"
trap 'kill "${server_pid:-}" "${target_pid:-}" 2>/dev/null || true
  xcrun simctl spawn "$udid" log show --style compact --start "$started" \
    --predicate "process == \"Sample\"" >"$out/simulator.log" 2>/dev/null || true' EXIT
start_server
start_target
target="http://127.0.0.1:$target_port"

xcrun simctl terminate "$udid" "$app" 2>/dev/null || true
xcrun simctl install "$udid" build/ios-sample/Build/Products/Debug-iphonesimulator/Sample.app
xcrun simctl launch "$udid" "$app" -host 127.0.0.1 -port "$port" -target "$target" -runAll YES >/dev/null
echo "e2e-ios: sample on $sim_name ($udid), server on port $port, target $target"

# One expectation per line, as `missing` in scripts/e2e_lib.sh reads them.
expected="GET $target/json ResponseReceived
POST $target/post ResponseReceived
GET $target/status/404 ResponseReceived
GET $target/delay/10 RequestFailed
PUT $target/put ResponseReceived
GET $target/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE
GET $target/bytes/4096 ResponseReceived"

assert_envelopes "$app" "$expected" "$timeout" "simulator log in $out/simulator.log" "127.0.0.1:$target_port"
