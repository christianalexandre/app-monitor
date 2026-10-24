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

fail() {
  echo "e2e-android: $*" >&2
  exit 1
}

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
: >"$server_log"

desktop/server/build/install/server/bin/server --port 0 >"$server_log" 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; adb logcat -d >"$out/logcat.txt" 2>/dev/null || true' EXIT
port=
for _ in $(seq 1 60); do
  port="$(sed -n 's/^Listening on port \([0-9]*\).*/\1/p' "$server_log")"
  [[ -n "$port" ]] && break
  kill -0 "$server_pid" 2>/dev/null || { cat "$server_log" >&2; fail "server exited before listening"; }
  sleep 1
done
[[ -n "$port" ]] || fail "server did not print its port, log in $server_log"

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

# One expectation per line: <method> <url> <ResponseReceived|RequestFailed> [<frame>...],
# a frame being <direction>:<opcode> of WebSocketFrame, e.g. DIRECTION_SENT:OPCODE_TEXT.
expected="GET https://httpbin.org/json ResponseReceived
POST https://httpbin.org/post ResponseReceived
GET https://httpbin.org/status/404 ResponseReceived
GET https://httpbin.org/delay/10 RequestFailed
GET https://echo.websocket.org/ ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE"

# Prints what is still missing from the server log, one line per expectation; nothing when all arrived.
missing() {
  grep -q "\] connected: .* $app " "$server_log" || echo "no device connected with app id $app"
  while read -r method url terminal frames; do
    id="$(awk -v m="$method" -v u="$url" '$2 == "network/RequestStarted" && $4 == m && $5 == u { print $3; exit }' "$server_log")"
    if [[ -z "$id" ]]; then
      echo "$method $url: no RequestStarted"
      continue
    fi
    grep -q " network/$terminal $id " "$server_log" || echo "$method $url: no $terminal for $id"
    for frame in $frames; do
      grep -q " network/WebSocketFrame $id ${frame%%:*} ${frame#*:}," "$server_log" \
        || echo "$method $url: no WebSocketFrame ${frame%%:*} ${frame#*:} for $id"
    done
  done <<<"$expected"
}

deadline=$((SECONDS + timeout))
while [[ -n "$(missing)" ]] && ((SECONDS < deadline)); do sleep 2; done
gaps="$(missing)"
if [[ -n "$gaps" ]]; then
  echo "$gaps" | sed 's/^/e2e-android: missing: /' >&2
  fail "failed after ${timeout}s, server log in $server_log, logcat in $out/logcat.txt"
fi
while read -r method url terminal frames; do
  echo "e2e-android: ok $method $url $terminal${frames:+ $frames}"
done <<<"$expected"
echo "e2e-android: passed, $(grep -c ' network/' "$server_log") envelopes"
