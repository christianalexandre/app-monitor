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

./gradlew -q :samples:android:assembleDebug :tools:headless-server:installDist :tools:e2e-target:installDist
mkdir -p "$out"
# Back as the developer had it: Android reads a setting never written as `null`, which `put` would store.
restore_auto_rotation() {
  case "${auto_rotation:-}" in
    "") ;;
    null) adb shell settings delete system accelerometer_rotation >/dev/null ;;
    *) adb shell settings put system accelerometer_rotation "$auto_rotation" ;;
  esac
}
trap 'kill "${server_pid:-}" "${target_pid:-}" 2>/dev/null || true; adb shell settings put system user_rotation 0 2>/dev/null || true; restore_auto_rotation 2>/dev/null || true; adb logcat -d >"$out/logcat.txt" 2>/dev/null || true' EXIT
# One rule for the last call of runAll, by then connected (ADR-0110); the target would answer 418.
mkdir -p "$out" && cat >"$out/mocks.json" <<JSON
{"rules": [{"id": 1, "method": "GET", "host": "$host", "path": "/status/418",
  "response": {"status_code": 200, "headers": [{"name": "Content-Type", "value": "text/plain"}], "body": "mocked"}}]}
JSON
# The first call of runAll is asked again, through the app's OkHttp client (ADR-0122).
# Not advertised: only the launch extras reach it, which the process death below relies on.
start_server --advertise false --mocks "$out/mocks.json" --replay /json
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
expected="GET $target/json ResponseReceived replay
POST $target/post ResponseReceived
GET $target/status/404 ResponseReceived
GET $target/delay/10 RequestFailed
GET $target/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE
GET $target/status/418 ResponseReceived mock:1"

assert_envelopes "$app" "$expected" "$timeout" "logcat in $out/logcat.txt" "$host:$target_port"
assert_app_name "$app" "AppMonitor Sample"
assert_no_drops "$app"

# $1 ROTATION_<n>'s n, $2 its user_rotation; fails if the display does not get there.
rotate() {
  adb shell settings put system user_rotation "$2"
  for _ in $(seq 1 15); do
    # Not a pipe into `grep -q`: its early exit breaks adb's pipe, a failure under pipefail.
    [[ "$(adb shell dumpsys window displays)" == *"mCurrentRotation=ROTATION_$1"* ]] && return 0
    sleep 1
  done
  fail "the display did not reach rotation $1"
}

started="$(grep -c ' network/RequestStarted ' "$server_log")"
connected="$(sessions "$app" kotlin)"
auto_rotation="$(adb shell settings get system accelerometer_rotation | tr -d '\r')"
adb shell settings put system accelerometer_rotation 0
rotate 90 1
sleep 3
rotate 0 0
sleep 3
[[ "$(sessions "$app" kotlin)" == "$connected" ]] || fail "a rotation opened a new session, server log in $server_log"
echo "$tag: ok two rotations, $connected session(s)"

adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am kill "$app"
[[ -z "$(adb shell pidof "$app" | tr -d '\r')" ]] || fail "am kill left the process running"
# No extras here: the restored task's own intent brings them back, with a saved state.
adb shell am start -W -n "$app/.MainActivity" >/dev/null
deadline=$((SECONDS + timeout))
while (($(sessions "$app" kotlin) <= connected)) && ((SECONDS < deadline)); do sleep 1; done
(($(sessions "$app" kotlin) == connected + 1)) || fail "no single new session after a process death, server log in $server_log"
sleep 3
[[ "$(grep -c ' network/RequestStarted ' "$server_log")" == "$started" ]] || fail "runAll ran again, server log in $server_log"
echo "$tag: ok process death reconnects once, runAll not again"
