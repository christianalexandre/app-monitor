#!/usr/bin/env bash
# Tests loop.sh's run_session/watchdog and loop_watch.sh, no Claude needed.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# No EXIT trap: bash 5 runs it in the watchdog subshells too, deleting $tmp.
tmp="$(mktemp -d)"

failures=0
check() { # name condition...
  local name="$1"
  shift
  if "$@"; then echo "  ok   $name"; else echo "  FAIL $name" >&2; failures=$((failures + 1)); fi
}

# shellcheck source=loop.sh
source "$here/loop.sh"
worktree="$tmp"

echo "test-loop: run_session"
timeout_s=30
start=$SECONDS
run_session "$tmp/short.jsonl" sh -c 'echo hi; exit 3'
code=$?
check "short command returns its exit code" test "$code" -eq 3
check "short command leaves no .timeout" test ! -e "$tmp/short.jsonl.timeout"
check "short command does not wait for the watchdog" test $((SECONDS - start)) -lt 5
check "output lands in the log" grep -qx hi "$tmp/short.jsonl"

timeout_s=1
run_session "$tmp/slow.jsonl" sleep 30
code=$?
check "slow command times out with 124" test "$code" -eq 124
check "slow command leaves a .timeout" test -e "$tmp/slow.jsonl.timeout"

echo "test-loop: watchdog"
timeout_s=30
sleep 30 &
target=$!
watchdog "$target" "$tmp/cancelled.timeout" &
dog=$!
sleep 0.3
kill -TERM "$dog"
wait "$dog"
check "cancelled watchdog leaves its target alive" kill -0 "$target"
check "cancelled watchdog leaves no .timeout" test ! -e "$tmp/cancelled.timeout"
kill "$target" 2>/dev/null
wait "$target" 2>/dev/null

echo "test-loop: result_of"
printf '%s\n' '{"type":"assistant"}' '{"type":"result","result":"report line 1\nreport line 2\nLOOP: CONTINUE"}' >"$tmp/result.jsonl"
check "multi-line report is kept whole" test "$(result_of "$tmp/result.jsonl")" = "$(printf 'report line 1\nreport line 2\nLOOP: CONTINUE')"
check "log without result gives nothing" test -z "$(result_of "$tmp/short.jsonl")"

echo "test-loop: stderr stays out of the jsonl"
run_session "$tmp/noisy.jsonl" sh -c 'echo "warning: not json" >&2; echo '"'"'{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}'"'"'; echo '"'"'{"type":"result","result":"LOOP: CONTINUE"}'"'"''
check "non-JSON stderr does not hide the verdict" test "$(result_of "$tmp/noisy.jsonl")" = "LOOP: CONTINUE"
check "non-JSON stderr does not hide rate-limit events" test "$(limit_status "$tmp/noisy.jsonl")" = allowed
check "stderr lands in the .err log" grep -qx "warning: not json" "$tmp/noisy.jsonl.err"

echo "test-loop: loop_watch"
mkdir -p "$tmp/repo/scripts" "$tmp/repo/.loop"
cp "$here/loop_watch.sh" "$tmp/repo/scripts/"
"$tmp/repo/scripts/loop_watch.sh" >/dev/null 2>"$tmp/watch.err"
code=$?
check "empty .loop exits 1" test "$code" -eq 1
check "empty .loop prints the no-session message" grep -q "no session in" "$tmp/watch.err"

rm -rf "$tmp"
((failures == 0)) && echo "test-loop: ok" || { echo "test-loop: $failures failed" >&2; exit 1; }
