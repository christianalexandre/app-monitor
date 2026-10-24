# Sourced by scripts/e2e_android.sh and scripts/e2e_ios.sh (ADR-0056, ADR-0057):
# the headless server and the local target on free ports, and the assertions on the log.
# The caller sets `tag` (the prefix of every line), `server_log` and `target_log`.

fail() {
  echo "$tag: $*" >&2
  exit 1
}

# $1 an installDist launcher that prints `Listening on port N`, $2 its log; starts it on port 0,
# sets listener_pid and listener_port.
listen() {
  : >"$2"
  "$1" --port 0 >"$2" 2>&1 &
  listener_pid=$!
  listener_port=
  for _ in $(seq 1 60); do
    listener_port="$(sed -n 's/^Listening on port \([0-9]*\).*/\1/p' "$2")"
    [[ -n "$listener_port" ]] && return 0
    kill -0 "$listener_pid" 2>/dev/null || { cat "$2" >&2; fail "$1 exited before listening"; }
    sleep 1
  done
  fail "$1 did not print its port, log in $2"
}

# Starts desktop/server's installDist, built by the caller; sets server_pid and port.
start_server() {
  listen desktop/server/build/install/server/bin/server "$server_log"
  server_pid=$listener_pid port=$listener_port
}

# Starts tools/e2e-target's installDist (ADR-0074), built by the caller, logging to $target_log;
# sets target_pid and target_port.
start_target() {
  listen tools/e2e-target/build/install/e2e-target/bin/e2e-target "$target_log"
  target_pid=$listener_pid target_port=$listener_port
}

# $1 the authority every request must go to (e.g. 10.0.2.2:N); prints the RequestStarted URLs elsewhere.
foreign_urls() {
  awk -v a="//$1/" '$2 == "network/RequestStarted" && index($5 "/", a) == 0 { print $4, $5 }' "$server_log"
}

# $1 app id, $2 one expectation per line: <method> <url> <ResponseReceived|RequestFailed> [<frame>...],
# a frame being <direction>:<opcode> of WebSocketFrame, e.g. DIRECTION_SENT:OPCODE_TEXT.
# Prints what is still missing from the server log, one line per expectation; nothing when all arrived.
missing() {
  local method url terminal frames id frame
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

# $1 app id, $2 expectations as for `missing`, $3 timeout (s), $4 where the device log is,
# $5 the target's authority as for `foreign_urls`.
# Waits until nothing is missing, then prints one `ok` line per expectation; fails otherwise.
assert_envelopes() {
  local deadline=$((SECONDS + $3)) gaps
  while [[ -n "$(missing "$1" "$2")" ]] && ((SECONDS < deadline)); do sleep 2; done
  gaps="$(missing "$1" "$2")"
  if [[ -n "$gaps" ]]; then
    echo "$gaps" | sed "s/^/$tag: missing: /" >&2
    fail "failed after $3s, server log in $server_log, $4"
  fi
  gaps="$(foreign_urls "$5")"
  if [[ -n "$gaps" ]]; then
    echo "$gaps" | sed "s/^/$tag: not on the target: /" >&2
    fail "requests left the local target $5, server log in $server_log"
  fi
  while read -r method url terminal frames; do
    echo "$tag: ok $method $url $terminal${frames:+ $frames}"
  done <<<"$2"
  echo "$tag: passed, $(grep -c ' network/' "$server_log") envelopes"
}
