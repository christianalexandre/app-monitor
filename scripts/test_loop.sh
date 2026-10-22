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
not_grep() { ! grep -q "$@"; }

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

timeout_s=30
run_session "$tmp/slow.jsonl" sh -c 'exit 3'
code=$?
check "run after a timeout on the same log returns its code" test "$code" -eq 3
check "run after a timeout clears the stale .timeout" test ! -e "$tmp/slow.jsonl.timeout"

# Ignoring TERM stands in for a session that exits as the deadline hits.
timeout_s=1
run_session "$tmp/late.jsonl" sh -c 'trap "" TERM; sleep 1.5; exit 5'
code=$?
check "session exiting by itself at the deadline returns its code" test "$code" -eq 5
check "session exiting by itself still sees the marker" test -e "$tmp/late.jsonl.timeout"

echo "test-loop: watchdog"
timeout_s=30
sleep 30 &
target=$!
watchdog "$target" "$tmp/cancelled.timeout" "$tmp/cancelled.done" &
dog=$!
sleep 0.3
touch "$tmp/cancelled.done"
start=$SECONDS
wait "$dog"
check "cancelled watchdog stops within a poll" test $((SECONDS - start)) -lt 2
check "cancelled watchdog leaves its target alive" kill -0 "$target"
check "cancelled watchdog leaves no .timeout" test ! -e "$tmp/cancelled.timeout"
kill "$target" 2>/dev/null
wait "$target" 2>/dev/null

# A TERM-cancelled watchdog leaked a sleep or hung on ~3% of these (ADR-0014).
poll_s=0.11
for _ in $(seq 20); do run_session "$tmp/instant.jsonl" true; done
check "instant sessions leave no sleep behind" test -z "$(pgrep -f "sleep $poll_s")"
check "instant sessions leave no .timeout" test ! -e "$tmp/instant.jsonl.timeout"
check "instant sessions leave no .done" test ! -e "$tmp/instant.jsonl.done"
poll_s=0.2

echo "test-loop: result_of"
printf '%s\n' '{"type":"assistant"}' '{"type":"result","result":"report line 1\nreport line 2\nLOOP: CONTINUE"}' >"$tmp/result.jsonl"
check "multi-line report is kept whole" test "$(result_of "$tmp/result.jsonl")" = "$(printf 'report line 1\nreport line 2\nLOOP: CONTINUE')"
check "log without result gives nothing" test -z "$(result_of "$tmp/short.jsonl")"

echo "test-loop: verdict_of"
printf 'report\nLOOP: CONTINUE \n' >"$tmp/space.md"
printf 'report\r\nLOOP: CONTINUE\r\n' >"$tmp/crlf.md"
printf 'LOOP: CONTINUE\nLOOP: STOP checkpoint\t\n' >"$tmp/stop.md"
printf 'no verdict here\n' >"$tmp/none.md"
printf 'report\nLOOP: CONTINUE — next task\n' >"$tmp/trailing.md"
printf 'report\nLOOP: STOPPED\n' >"$tmp/stopped.md"
check "trailing space still continues" test "$(verdict_of "$tmp/space.md")" = "LOOP: CONTINUE"
check "trailing CR still continues" test "$(verdict_of "$tmp/crlf.md")" = "LOOP: CONTINUE"
check "last verdict wins, trimmed" test "$(verdict_of "$tmp/stop.md")" = "LOOP: STOP checkpoint"
check "report without verdict gives nothing" test -z "$(verdict_of "$tmp/none.md")"
check "CONTINUE with trailing text is no verdict" test -z "$(verdict_of "$tmp/trailing.md")"
check "STOP glued to a word is no verdict" test -z "$(verdict_of "$tmp/stopped.md")"

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

event() { printf '{"type":"assistant","message":{"content":[{"type":"text","text":"%s"}]}}\n' "$1"; }
event "first session" >"$tmp/repo/.loop/a.jsonl"
touch -t 202001010000 "$tmp/repo/.loop/a.jsonl"
LOOP_WATCH_POLL=0.2 "$tmp/repo/scripts/loop_watch.sh" >"$tmp/watch.out" 2>&1 &
watcher=$!
sleep 1
event "second session" >"$tmp/repo/.loop/b.jsonl"
for _ in $(seq 25); do grep -q "second session" "$tmp/watch.out" && break; sleep 0.2; done
kill "$watcher" 2>/dev/null
wait "$watcher" 2>/dev/null
check "watcher prints the first session's events" grep -q "💬 first session" "$tmp/watch.out"
check "watcher switches to a newer session" grep -q "💬 second session" "$tmp/watch.out"
check "stopped watcher leaves no tail behind" test -z "$(pgrep -f "tail -n +1 -F $tmp/repo")"

echo "test-loop: make loop-worktree"
git() { command git -c user.name=test -c user.email=test@example.com "$@"; }
git init -q -b dev "$tmp/origin" && git -C "$tmp/origin" commit -q --allow-empty -m init
git clone -q "$tmp/origin" "$tmp/clone"
mkdir -p "$tmp/clone/scripts"
cp "$here/loop.sh" "$tmp/clone/scripts/"
cp "$here/../Makefile" "$tmp/clone/"
LOOP_WORKTREE="$tmp/wt" make -s -C "$tmp/clone" loop-worktree >"$tmp/wt.out" 2>&1
code=$?
unset -f git
check "loop-worktree exits 0" test "$code" -eq 0
check "loop-worktree honours LOOP_WORKTREE" test -e "$tmp/wt/.git"
check "loop-worktree prints the worktree" grep -qx "worktree: $tmp/wt" "$tmp/wt.out"

echo "test-loop: loop without a verdict"
mkdir -p "$tmp/bin"
printf '#!/bin/sh\ncat "$FAKE_SESSION"\n' >"$tmp/bin/claude"
printf '#!/bin/sh\n' >"$tmp/bin/osascript"
chmod +x "$tmp/bin/claude" "$tmp/bin/osascript"
session() { # status report
  printf '{"type":"rate_limit_event","rate_limit_info":{"status":"%s"}}\n{"type":"result","result":"%s"}\n' "$1" "$2"
}
fake_loop() { # session file
  FAKE_SESSION="$1" PATH="$tmp/bin:$PATH" LOOP_WORKTREE="$tmp/wt" MAX_ITERATIONS=1 LIMIT_RETRIES=0 \
    "$tmp/clone/scripts/loop.sh" >"$1.out" 2>&1
}
session allowed "crashed while waiting on a rate limit" >"$tmp/crash.jsonl"
fake_loop "$tmp/crash.jsonl"
code=$?
check "report mentioning a rate limit exits 1" test "$code" -eq 1
check "report mentioning a rate limit stops with no verdict" grep -q "no verdict line" "$tmp/crash.jsonl.out"
check "report mentioning a rate limit does not wait" not_grep "usage limit" "$tmp/crash.jsonl.out"
session rejected "out of usage" >"$tmp/limited.jsonl"
fake_loop "$tmp/limited.jsonl"
code=$?
check "rejected limit event exits 1" test "$code" -eq 1
check "rejected limit event waits for the limit" grep -q "usage limit: gave up after 0 waits" "$tmp/limited.jsonl.out"
session allowed 'done\nLOOP: CONTINUE — next task' >"$tmp/trailing.jsonl"
fake_loop "$tmp/trailing.jsonl"
code=$?
check "CONTINUE with trailing text exits 1" test "$code" -eq 1
check "CONTINUE with trailing text stops with no verdict" grep -q "no verdict line" "$tmp/trailing.jsonl.out"

rm -rf "$tmp"
((failures == 0)) && echo "test-loop: ok" || { echo "test-loop: $failures failed" >&2; exit 1; }
