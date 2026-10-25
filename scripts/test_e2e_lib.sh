#!/usr/bin/env bash
# Tests the assertions of e2e_lib.sh (`missing`, `repeated`, `sessions`, `foreign_urls`) on a crafted
# server log; needs no device.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/.."

failures=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "  ok   $1"; else
    echo "  FAIL $1: expected '$2', got '$3'" >&2
    failures=$((failures + 1))
  fi
}

mkdir -p build/test-e2e-lib
server_log=build/test-e2e-lib/server.log
tag=test
source scripts/e2e_lib.sh

t=http://127.0.0.1:9
cat >"$server_log" <<LOG
[s1] connected: iPhone18,1 Simulator (PLATFORM_IOS) dev.app 1.0 kotlin
[s1] network/RequestStarted a GET $t/json
[s1] network/ResponseReceived a 200, 2 bytes
[s2] network/RequestStarted b GET $t/json
[s1] network/RequestStarted c GET ws://127.0.0.1:9/ws
[s1] network/WebSocketFrame c DIRECTION_SENT OPCODE_TEXT, 5 bytes
[s1] network/RequestStarted d GET https://httpbin.org/json
[s1] network/RequestStarted m GET $t/status/418 mock 1
[s1] network/ResponseReceived m 200, 6 bytes
[s1] network/RequestStarted n GET $t/status/404
[s1] network/ResponseReceived n 404, 0 bytes
LOG

check "nothing missing" "" "$(missing dev.app "GET $t/json ResponseReceived")"
check "missing terminal and frame" "GET ws://127.0.0.1:9/ws: no ResponseReceived for c
GET ws://127.0.0.1:9/ws: no WebSocketFrame DIRECTION_RECEIVED OPCODE_TEXT for c" \
  "$(missing dev.app "GET ws://127.0.0.1:9/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT")"
check "answered by its mock rule" "" "$(missing dev.app "GET $t/status/418 ResponseReceived mock:1")"
check "another rule or none" "GET $t/status/418: not answered by mock rule 2
GET $t/status/404: not answered by mock rule 1" "$(missing dev.app "GET $t/status/418 ResponseReceived mock:2
GET $t/status/404 ResponseReceived mock:1")"
check "missing request and device" "no device connected with app id other.app
POST $t/post: no RequestStarted" "$(missing other.app "POST $t/post ResponseReceived")"
check "captured twice" "GET $t/json: 2 RequestStarted" "$(repeated "GET $t/json ResponseReceived
GET ws://127.0.0.1:9/ws ResponseReceived")"
check "off the target, any scheme on it kept" "GET https://httpbin.org/json" "$(foreign_urls 127.0.0.1:9)"

check "the same socket under ws and http" "GET ws://127.0.0.1:9/ws: 2 RequestStarted" "$(
  echo "[s2] network/RequestStarted e GET http://127.0.0.1:9/ws" >>"$server_log"
  repeated "GET ws://127.0.0.1:9/ws ResponseReceived"
)"
check "wss and https the same, http and https not" "GET wss://h/ws: 2 RequestStarted" "$(
  printf '%s\n' "[s1] network/RequestStarted f GET wss://h/ws" "[s2] network/RequestStarted g GET https://h/ws" \
    "[s1] network/RequestStarted i GET http://h/x" "[s2] network/RequestStarted j GET https://h/x" >>"$server_log"
  repeated "GET wss://h/ws ResponseReceived
GET http://h/x ResponseReceived"
)"
check "sdk/core reconnecting is no Swift SDK session" "2 0" "$(
  echo "[s3] connected: iPhone18,1 Simulator (PLATFORM_IOS) dev.app 1.0 kotlin" >>"$server_log"
  echo "$(sessions dev.app kotlin) $(sessions dev.app swift)"
)"
check "a session per SDK, empty app version" "1 1" "$(
  echo "[s4] connected: iPhone18,1 Simulator (PLATFORM_IOS) other.app  swift" >>"$server_log"
  echo "[s5] connected: iPhone18,1 Simulator (PLATFORM_IOS) other.app 1.0 kotlin" >>"$server_log"
  echo "$(sessions other.app kotlin) $(sessions other.app swift)"
)"
check "an app id in the device name is not the app's" "1" "$(
  echo "[s6] connected: other.app (PLATFORM_ANDROID) third.app 1.0 kotlin" >>"$server_log"
  sessions other.app kotlin
)"
check "the proxy's exchanges are not off the target" "GET https://httpbin.org/json
GET wss://h/ws
GET https://h/ws
GET http://h/x
GET https://h/x" "$(
  echo "[proxy] network/RequestStarted k POST https://127.0.0.1:10/grpcbin.GRPCBin/DummyUnary" >>"$server_log"
  foreign_urls 127.0.0.1:9
)"

if ((failures > 0)); then
  echo "e2e_lib: $failures failure(s)" >&2
  exit 1
fi
echo "e2e_lib: all tests passed"
