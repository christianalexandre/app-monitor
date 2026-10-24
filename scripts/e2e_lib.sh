# Sourced by scripts/e2e_android.sh and scripts/e2e_ios.sh (ADR-0056, ADR-0057):
# the headless server on a free port and the assertions on its log.
# The caller sets `tag` (the prefix of every line) and `server_log`.

fail() {
  echo "$tag: $*" >&2
  exit 1
}

# Starts desktop/server's installDist, built by the caller, on port 0; sets server_pid and port.
start_server() {
  : >"$server_log"
  desktop/server/build/install/server/bin/server --port 0 >"$server_log" 2>&1 &
  server_pid=$!
  port=
  for _ in $(seq 1 60); do
    port="$(sed -n 's/^Listening on port \([0-9]*\).*/\1/p' "$server_log")"
    [[ -n "$port" ]] && return 0
    kill -0 "$server_pid" 2>/dev/null || { cat "$server_log" >&2; fail "server exited before listening"; }
    sleep 1
  done
  fail "server did not print its port, log in $server_log"
}

# $1 app id, $2 one expectation per line: <method> <url> <ResponseReceived|RequestFailed> [<frame>...],
# a frame being <direction>:<opcode> of WebSocketFrame, e.g. DIRECTION_SENT:OPCODE_TEXT.
# Prints what is still missing from the server log, one line per expectation; nothing when all arrived.
missing() {
  grep -q "\] connected: .* $1 " "$server_log" || echo "no device connected with app id $1"
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
  done <<<"$2"
}

# $1 app id, $2 expectations as for `missing`, $3 timeout (s), $4 where the device log is.
# Waits until nothing is missing, then prints one `ok` line per expectation; fails otherwise.
assert_envelopes() {
  local deadline=$((SECONDS + $3)) gaps
  while [[ -n "$(missing "$1" "$2")" ]] && ((SECONDS < deadline)); do sleep 2; done
  gaps="$(missing "$1" "$2")"
  if [[ -n "$gaps" ]]; then
    echo "$gaps" | sed "s/^/$tag: missing: /" >&2
    fail "failed after $3s, server log in $server_log, $4"
  fi
  while read -r method url terminal frames; do
    echo "$tag: ok $method $url $terminal${frames:+ $frames}"
  done <<<"$2"
  echo "$tag: passed, $(grep -c ' network/' "$server_log") envelopes"
}
