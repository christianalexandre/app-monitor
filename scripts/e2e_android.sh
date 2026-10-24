#!/usr/bin/env bash
# Android end to end (ADR-0056): the sample on a booted emulator against the
# headless server, every expected envelope asserted in the server log.
# ANDROID_SERIAL picks the emulator, E2E_TIMEOUT (s, default 120) bounds the wait.
set -euo pipefail
cd "$(dirname "$0")/.."

out=build/e2e-android
server_log=$out/server.log
timeout="${E2E_TIMEOUT:-120}"
app=appmonitor.sample
tag=e2e-android
source scripts/e2e_lib.sh

PATH="$PATH:${ANDROID_HOME:-}/platform-tools"
command -v adb >/dev/null 2>&1 || fail "adb not found (ANDROID_HOME/platform-tools, or adb on PATH)"
serial="${ANDROID_SERIAL:-$(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1; exit }')}"
[[ -n "$serial" ]] || fail "no booted emulator in 'adb devices' (start one, or set ANDROID_SERIAL)"
export ANDROID_SERIAL="$serial"
for _ in $(seq 1 90); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || fail "$serial did not finish booting"

./gradlew -q :samples:android:assembleDebug :desktop:server:installDist
mkdir -p "$out"
trap 'kill "${server_pid:-}" 2>/dev/null || true; adb logcat -d >"$out/logcat.txt" 2>/dev/null || true' EXIT
start_server

adb install -r samples/android/build/outputs/apk/debug/android-debug.apk >/dev/null
# The local network prompt (ADR-0030) would wait for a hand; grant it up front.
if (($(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 37)); then
  adb shell pm grant "$app" android.permission.ACCESS_LOCAL_NETWORK
fi
adb logcat -c
# A running activity would get the extras in onNewIntent, which the sample ignores; clear its task.
adb shell am start -S -W --activity-clear-task -n "$app/.MainActivity" \
  --es host "${E2E_HOST:-10.0.2.2}" --ei port "$port" --ez runAll true >/dev/null
echo "e2e-android: sample on $serial, server on port $port"

# One expectation per line, as `missing` in scripts/e2e_lib.sh reads them.
expected="GET https://httpbin.org/json ResponseReceived
POST https://httpbin.org/post ResponseReceived
GET https://httpbin.org/status/404 ResponseReceived
GET https://httpbin.org/delay/10 RequestFailed
GET https://echo.websocket.org/ ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE"

assert_envelopes "$app" "$expected" "$timeout" "logcat in $out/logcat.txt"
