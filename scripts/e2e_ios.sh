#!/usr/bin/env bash
# iOS end to end (ADR-0057): the sample's Debug app on an iPhone simulator against
# the headless server, its calls to the local target (ADR-0074), every expected envelope
# asserted in the server log. `--grpc` builds the sample with gRPC-Core and asserts its call through
# the server's proxy to the target's TLS gRPC origin (ADR-0108, ADR-0163).
# SIM_DEVICE picks the simulator (booted first), E2E_TIMEOUT (s, default 120) bounds the wait.
set -euo pipefail
cd "$(dirname "$0")/.."

grpc="${1:-}"
[[ -z "$grpc" || "$grpc" == --grpc ]] || { echo "usage: e2e_ios.sh [--grpc]" >&2; exit 2; }
out=build/e2e-ios${grpc:+-grpc}
server_log=$out/server.log
target_log=$out/target.log
timeout="${E2E_TIMEOUT:-120}"
app=dev.appmonitor.sample
tag=e2e-ios
source scripts/e2e_lib.sh

mkdir -p "$out"
boot_simulator
if [[ -n "$grpc" ]]; then
  derived=build/ios-sample-grpc
  build_ios_debug samples/ios/grpc.yml Sample "$derived" SampleGrpc
else
  derived=build/ios-sample
  build_ios_debug samples/ios/project.yml Sample "$derived"
fi

./gradlew -q :desktop:server:installDist :tools:e2e-target:installDist
started="$(date '+%Y-%m-%d %H:%M:%S')"
trap 'kill "${server_pid:-}" "${target_pid:-}" 2>/dev/null || true
  xcrun simctl spawn "$udid" log show --style compact --start "$started" \
    --predicate "process == \"Sample\"" >"$out/simulator.log" 2>/dev/null || true' EXIT
rm -rf "$out/proxy"
# $1 a sed pattern whose group 1 is the value, $2 the log; waits up to 10 s for the line, as `listen` does.
logged() {
  local value
  for _ in $(seq 1 10); do
    value="$(sed -n "s/$1/\1/p" "$2")"
    [[ -n "$value" ]] && { echo "$value"; return 0; }
    sleep 1
  done
}
# One rule for the sample's 418 call, by then connected (ADR-0110); the target would answer 418. The first
# call is asked again, through the app's URLSession (ADR-0124).
cat >"$out/mocks.json" <<JSON
{"rules": [{"id": 1, "method": "GET", "host": "127.0.0.1", "path": "/status/418",
  "response": {"status_code": 200, "headers": [{"name": "Content-Type", "value": "text/plain"}], "body": "mocked"}}]}
JSON
launch=()
if [[ -n "$grpc" ]]; then
  start_target --grpc-trust "$out/grpc-trust.p12"
  grpc_port="$(logged '^gRPC on port \([0-9]*\)$' "$target_log")"
  [[ -n "$grpc_port" ]] || fail "the target printed no gRPC port, log in $target_log"
  # The proxy verifies origins against the JVM's trust store; this one holds the target's certificate only.
  SERVER_OPTS="-Djavax.net.ssl.trustStore=$out/grpc-trust.p12 -Djavax.net.ssl.trustStorePassword=e2e-target" \
    start_server --proxy "$out/proxy" --mocks "$out/mocks.json" --replay /json
  proxy_port="$(logged '^Proxy on port \([0-9]*\),.*' "$server_log")"
  [[ -n "$proxy_port" ]] || fail "the server printed no proxy port, log in $server_log"
  export SIMCTL_CHILD_grpc_proxy="http://127.0.0.1:$proxy_port"
  export SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH="$PWD/$out/proxy/proxy-ca.pem"
  launch=(-grpcTarget "127.0.0.1:$grpc_port")
else
  start_target
  start_server --mocks "$out/mocks.json" --replay /json
fi
target="http://127.0.0.1:$target_port"

xcrun simctl terminate "$udid" "$app" 2>/dev/null || true
xcrun simctl install "$udid" "$derived/Build/Products/Debug-iphonesimulator/Sample.app"
xcrun simctl launch "$udid" "$app" -host 127.0.0.1 -port "$port" -target "$target" \
  ${launch[@]+"${launch[@]}"} -runAll YES >/dev/null
echo "e2e-ios: sample on $sim_name ($udid), server on port $port, target $target${grpc:+, proxy on $proxy_port}"

# One expectation per line, as `missing` in scripts/e2e_lib.sh reads them.
expected="GET $target/json ResponseReceived replay
POST $target/post ResponseReceived
GET $target/status/404 ResponseReceived
GET $target/delay/10 RequestFailed
PUT $target/put ResponseReceived
GET $target/ws ResponseReceived DIRECTION_SENT:OPCODE_TEXT DIRECTION_RECEIVED:OPCODE_TEXT DIRECTION_SENT:OPCODE_CLOSE
GET $target/bytes/4096 ResponseReceived
GET $target/status/418 ResponseReceived mock:1"
[[ -z "$grpc" ]] || expected+="
POST https://127.0.0.1:$grpc_port/grpcbin.GRPCBin/DummyUnary ResponseReceived DIRECTION_SENT:OPCODE_BINARY DIRECTION_RECEIVED:OPCODE_BINARY"

assert_envelopes "$app" "$expected" "$timeout" "simulator log in $out/simulator.log" "127.0.0.1:$target_port"
