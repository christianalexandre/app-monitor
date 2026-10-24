# Sourced by scripts/e2e_android.sh, scripts/e2e_ios.sh and scripts/e2e_kmp.sh (ADR-0056, ADR-0057):
# the device, the headless server and the local target on free ports, and the assertions on the log.
# The caller sets `tag` (the prefix of every line), `server_log` and `target_log`.

fail() {
  echo "$tag: $*" >&2
  exit 1
}

# Picks ANDROID_SERIAL, else the first booted emulator, and waits for its boot; sets and exports
# ANDROID_SERIAL, puts adb on the PATH.
boot_emulator() {
  PATH="$PATH:${ANDROID_HOME:-}/platform-tools"
  command -v adb >/dev/null 2>&1 || fail "adb not found (ANDROID_HOME/platform-tools, or adb on PATH)"
  ANDROID_SERIAL="${ANDROID_SERIAL:-$(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1; exit }')}"
  [[ -n "$ANDROID_SERIAL" ]] || fail "no booted emulator in 'adb devices' (start one, or set ANDROID_SERIAL)"
  export ANDROID_SERIAL
  for _ in $(seq 1 90); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
    sleep 2
  done
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || fail "$ANDROID_SERIAL did not finish booting"
}

# $1 the app id: grants the local network permission (ADR-0030), whose prompt would wait for a hand.
grant_local_network() {
  if (($(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 37)); then
    adb shell pm grant "$1" android.permission.ACCESS_LOCAL_NETWORK
  fi
}

# Picks the booted iPhone simulator named SIM_DEVICE (any when unset), else boots an available one;
# sets udid and sim_name.
boot_simulator() {
  command -v xcrun >/dev/null 2>&1 || fail "xcrun not found (needs macOS with Xcode; ADR-0047)"
  local pick
  pick="$(xcrun simctl list devices booted | scripts/sim_list.sh | awk -v d="${SIM_DEVICE:-}" 'd == "" || substr($0, 38) == d' | head -1)"
  if [[ -z "$pick" ]]; then
    pick="$(xcrun simctl list devices available | scripts/sim_list.sh | awk -v d="${SIM_DEVICE:-}" 'd == "" || substr($0, 38) == d' | head -1)"
    [[ -n "$pick" ]] || fail "no iPhone simulator${SIM_DEVICE:+ named $SIM_DEVICE} (Xcode > Settings > Components)"
    xcrun simctl boot "${pick%% *}"
  fi
  udid="${pick%% *}" sim_name="${pick#* }"
  xcrun simctl bootstatus "$udid" -b >/dev/null
}

# $1 an XcodeGen spec, $2 its scheme, $3 the derived data path: builds the Debug app for the picked
# simulator, log in $out/build.log. Release and its SDK name scan are `make swift`'s (ADR-0075).
build_ios_debug() {
  command -v xcodegen >/dev/null 2>&1 || fail "xcodegen not found ('brew install xcodegen'; ADR-0050)"
  xcodegen generate --quiet --spec "$1"
  xcodebuild build -project "$(dirname "$1")/$2.xcodeproj" -scheme "$2" -configuration Debug \
    -destination "id=$udid" -derivedDataPath "$3" >"$out/build.log" 2>&1 \
    || { grep -E 'error:' "$out/build.log" >&2 || true; fail "iOS Debug build failed, log in $out/build.log"; }
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

# $2 expectations as for `missing`: prints each one whose RequestStarted is in the log more than once (ADR-0003).
repeated() {
  local method url count
  while read -r method url _; do
    count="$(awk -v m="$method" -v u="$url" '$2 == "network/RequestStarted" && $4 == m && $5 == u { n++ } END { print n + 0 }' "$server_log")"
    ((count <= 1)) || echo "$method $url: $count RequestStarted"
  done <<<"$1"
}

# $1 app id, $2 expectations as for `missing`, $3 timeout (s), $4 where the device log is,
# $5 the target's authority as for `foreign_urls`.
# Waits until nothing is missing, then a settle for late duplicates, and prints one `ok` line per
# expectation; fails on a gap, a duplicate or a request off the target.
assert_envelopes() {
  local deadline=$((SECONDS + $3)) gaps method url terminal frames
  while [[ -n "$(missing "$1" "$2")" ]] && ((SECONDS < deadline)); do sleep 2; done
  gaps="$(missing "$1" "$2")"
  if [[ -n "$gaps" ]]; then
    echo "$gaps" | sed "s/^/$tag: missing: /" >&2
    fail "failed after $3s, server log in $server_log, $4"
  fi
  sleep 3
  gaps="$(repeated "$2")"
  if [[ -n "$gaps" ]]; then
    echo "$gaps" | sed "s/^/$tag: captured twice: /" >&2
    fail "an exchange was captured more than once, server log in $server_log"
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
