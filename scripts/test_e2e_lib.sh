#!/usr/bin/env bash
# Tests the assertions of e2e_lib.sh (`missing`, `repeated`, `foreign_urls`) on a crafted
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
[s1] connected: iPhone18,1 Simulator (PLATFORM_IOS) dev.app 1.0
[s1] network/RequestStarted a GET $t/json
[s1] network/ResponseReceived a 200, 2 bytes
[s2] network/RequestStarted b GET $t/json
[s1] network/RequestStarted c GET ws://127.0.0.1:9/ws
[s1] network/WebSocketFrame c DIRECTION_SENT OPCODE_TEXT, 5 bytes
[s1] network/RequestStarted d GET https://httpbin.org/json
LOG

check "nothing missing" "" "$(missing dev.app "GET $t/json ResponseReceived")"
check "missing terminal and frame" "GET ws://127.0.0.1:9/ws: no ResponseReceived for c
GET ws://127.0.0.1:9/ws: no WebSocketFrame DIRECTION_RECEIVED OPCODE_TEXT for c" \
  "$(missing dev.app "GET ws://127.0.0.1:9/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT")"
check "missing request and device" "no device connected with app id other.app
POST $t/post: no RequestStarted" "$(missing other.app "POST $t/post ResponseReceived")"
check "captured twice" "GET $t/json: 2 RequestStarted" "$(repeated "GET $t/json ResponseReceived
GET ws://127.0.0.1:9/ws ResponseReceived")"
check "off the target, any scheme on it kept" "GET https://httpbin.org/json" "$(foreign_urls 127.0.0.1:9)"

if ((failures > 0)); then
  echo "e2e_lib: $failures failure(s)" >&2
  exit 1
fi
echo "e2e_lib: all tests passed"
