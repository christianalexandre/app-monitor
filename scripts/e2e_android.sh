#!/usr/bin/env bash
# Android end to end (ADR-0056): the sample on a booted emulator against the
# headless server, its calls to the local target (ADR-0074), every expected envelope asserted
# in the server log.
# ANDROID_SERIAL picks the emulator, E2E_TIMEOUT (s, default 120) bounds the wait.
set -euo pipefail
cd "$(dirname "$0")/.."

out=build/e2e-android
server_log=$out/server.log
target_log=$out/target.log
host="${E2E_HOST:-10.0.2.2}"
timeout="${E2E_TIMEOUT:-120}"
app=appmonitor.sample
tag=e2e-android
source scripts/e2e_lib.sh

boot_emulator
serial=$ANDROID_SERIAL

./gradlew -q :samples:android:assembleDebug :desktop:server:installDist :tools:e2e-target:installDist
mkdir -p "$out"
trap 'kill "${server_pid:-}" "${target_pid:-}" 2>/dev/null || true; adb logcat -d >"$out/logcat.txt" 2>/dev/null || true' EXIT
start_server
start_target
target="http://$host:$target_port"

adb install -r samples/android/build/outputs/apk/debug/android-debug.apk >/dev/null
grant_local_network "$app"
adb logcat -c
# A running activity would get the extras in onNewIntent, which the sample ignores; clear its task.
adb shell am start -S -W --activity-clear-task -n "$app/.MainActivity" \
  --es host "$host" --ei port "$port" --es target "$target" --ez runAll true >/dev/null
echo "e2e-android: sample on $serial, server on port $port, target $target"

# One expectation per line, as `missing` in scripts/e2e_lib.sh reads them.
expected="GET $target/json ResponseReceived
POST $target/post ResponseReceived
GET $target/status/404 ResponseReceived
GET $target/delay/10 RequestFailed
GET $target/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE"

assert_envelopes "$app" "$expected" "$timeout" "logcat in $out/logcat.txt" "$host:$target_port"
