#!/usr/bin/env bash
# Runs /next in a fresh Claude Code session per iteration until it reports STOP.
# Env: MAX_ITERATIONS (default 20), MAX_BUDGET_USD (per iteration, optional).
set -uo pipefail
cd "$(dirname "$0")/.."

max="${MAX_ITERATIONS:-20}"
mkdir -p .loop

notify() {
  echo "loop: $1"
  command -v osascript >/dev/null && osascript -e "display notification \"$1\" with title \"app-monitor loop\"" || true
}

for ((i = 1; i <= max; i++)); do
  log=".loop/$(date +%Y%m%d-%H%M%S)-$i.log"
  echo "loop: iteration $i/$max → $log"
  claude -p "/next" --permission-mode auto ${MAX_BUDGET_USD:+--max-budget-usd "$MAX_BUDGET_USD"} 2>&1 | tee "$log"
  verdict="$(grep -E '^LOOP: (CONTINUE|STOP)' "$log" | tail -1)"
  if [[ "$verdict" != "LOOP: CONTINUE" ]]; then
    notify "stopped after $i iteration(s): ${verdict:-no verdict line} — see $log"
    exit 0
  fi
done
notify "reached MAX_ITERATIONS=$max"
