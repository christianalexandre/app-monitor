#!/usr/bin/env bash
# Tests loop.sh's run_session/watchdog and loop_watch.sh, no Claude needed.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

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

echo "test-loop: loop_watch"
mkdir -p "$tmp/repo/scripts" "$tmp/repo/.loop"
cp "$here/loop_watch.sh" "$tmp/repo/scripts/"
"$tmp/repo/scripts/loop_watch.sh" >/dev/null 2>"$tmp/watch.err"
code=$?
check "empty .loop exits 1" test "$code" -eq 1
check "empty .loop prints the no-session message" grep -q "no session in" "$tmp/watch.err"

((failures == 0)) && echo "test-loop: ok" || { echo "test-loop: $failures failed" >&2; exit 1; }
