#!/usr/bin/env bash
# Runs /next in a fresh session per iteration in a dedicated worktree until it
# reports STOP; `--worktree` only creates the worktree. Env (defaults):
#   MAX_ITERATIONS=20  MAX_BUDGET_USD=20 (per iteration)  ITERATION_TIMEOUT=5400 (s)
#   LIMIT_RETRIES=8    LOOP_WORKTREE=../<repo>-loop
set -uo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
worktree="${LOOP_WORKTREE:-$(dirname "$repo")/$(basename "$repo")-loop}"
max="${MAX_ITERATIONS:-20}"
budget="${MAX_BUDGET_USD:-20}"
timeout_s="${ITERATION_TIMEOUT:-5400}"
limit_retries="${LIMIT_RETRIES:-8}"
poll_s=0.2
logs="$repo/.loop"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

notify() {
  echo "loop: $1"
  command -v osascript >/dev/null && osascript -e "display notification \"$1\" with title \"app-monitor loop\"" || true
}

ensure_worktree() {
  git -C "$repo" fetch -q origin --prune
  [[ -d "$worktree" ]] && return
  git -C "$repo" worktree add -q --detach "$worktree" origin/dev
  echo "loop: created worktree $worktree"
}

# Kills $1 after $timeout_s seconds and touches $2, unless $3 appears first.
# Polled, never cancelled by a signal: ADR-0014.
watchdog() {
  local deadline=$((SECONDS + timeout_s))
  while ((SECONDS < deadline)); do
    [[ -e "$3" ]] && return 0
    sleep "$poll_s"
  done
  [[ -e "$3" ]] || { kill -TERM "$1" 2>/dev/null && touch "$2"; }
}

# Runs "${@:2}" in the worktree, stdout into $1, stderr into $1.err (jq reads
# $1 and stops at the first non-JSON line). Exit 124 means it timed out.
run_session() {
  local log="$1"
  shift
  rm -f "$log.done"
  (cd "$worktree" && exec "$@") >"$log" 2>"$log.err" &
  local pid=$!
  watchdog "$pid" "$log.timeout" "$log.done" &
  local watchdog_pid=$!
  wait "$pid"
  local code=$?
  touch "$log.done"
  wait "$watchdog_pid"
  rm -f "$log.done"
  [[ -e "$log.timeout" ]] && return 124
  return "$code"
}

result_of() { jq -c 'select(.type == "result")' "$1" 2>/dev/null | tail -1 | jq -r '.result // empty'; }
cost_of() { jq -r 'select(.type == "result") | .total_cost_usd // empty' "$1" 2>/dev/null | tail -1; }
limit_status() { jq -r 'select(.type == "rate_limit_event") | .rate_limit_info.status' "$1" 2>/dev/null | tail -1; }
# Last verdict line of report $1, trailing whitespace and CR stripped.
verdict_of() { sed 's/[[:space:]]*$//' "$1" | grep -E '^LOOP: (CONTINUE|STOP)' | tail -1; }
limit_reset() { jq -r 'select(.type == "rate_limit_event") | .rate_limit_info.resetsAt' "$1" 2>/dev/null | tail -1; }

# Sourced by scripts/test_loop.sh, which only needs the functions above.
[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0

if [[ "${1:-}" == --worktree ]]; then
  ensure_worktree || exit 1
  echo "worktree: $worktree"
  exit 0
fi
mkdir -p "$logs"
command -v jq >/dev/null || { echo "loop: jq is required" >&2; exit 1; }
ensure_worktree
retries=0
i=1
while ((i <= max)); do
  base="$logs/$(date +%Y%m%d-%H%M%S)-$i"
  echo "loop: iteration $i/$max → $base.md (live: make loop-watch)"
  run_session "$base.jsonl" claude -p "/next" --permission-mode auto \
    --output-format stream-json --verbose --max-budget-usd "$budget"
  code=$?
  result="$(result_of "$base.jsonl")"
  printf '%s\n' "$result" >"$base.md"
  echo "$result"
  echo "loop: cost \$$(cost_of "$base.jsonl")"

  if ((code == 124)); then
    notify "iteration $i timed out after ${timeout_s}s — see $base.jsonl"
    exit 1
  fi

  verdict="$(verdict_of "$base.md")"
  if [[ -z "$verdict" ]]; then
    status="$(limit_status "$base.jsonl")"
    if [[ -n "$status" && "$status" != allowed* ]] || grep -qiE 'usage limit|rate limit' "$base.md"; then
      if ((retries >= limit_retries)); then
        notify "usage limit: gave up after $retries waits"
        exit 1
      fi
      reset="$(limit_reset "$base.jsonl")"
      now="$(date +%s)"
      if [[ "$reset" =~ ^[0-9]+$ ]]; then
        wait_s=$((reset > now ? reset - now + 120 : 60))
      else
        wait_s=1800
      fi
      retries=$((retries + 1))
      notify "usage limit ($status) — sleeping ${wait_s}s, then resuming iteration $i"
      sleep "$wait_s"
      continue
    fi
    notify "stopped after $i iteration(s): no verdict line (exit $code) — see $base.jsonl{,.err}"
    exit 1
  fi

  retries=0
  if [[ "$verdict" != "LOOP: CONTINUE" ]]; then
    notify "stopped after $i iteration(s): $verdict"
    exit 0
  fi
  i=$((i + 1))
done
notify "reached MAX_ITERATIONS=$max"
