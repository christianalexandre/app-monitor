#!/usr/bin/env bash
# Follows the newest loop session live: assistant text and tool calls.
# Switches to a newer session as soon as the loop starts it.
set -euo pipefail
logs="$(cd "$(dirname "$0")/.." && pwd)/.loop"
poll_s="${LOOP_WATCH_POLL:-2}"

newest() { ls -t "$logs"/*.jsonl 2>/dev/null | head -1 || true; }

render() {
  jq --unbuffered -r '
    if .type == "assistant" then
      .message.content[]
      | if .type == "text" then "💬 " + .text
        elif .type == "tool_use" then
          "🔧 " + .name + "  " + ((.input.command // .input.file_path // .input.pattern // .input.skill // "") | tostring | .[0:160])
        else empty end
    elif .type == "result" then "✅ done — $" + ((.total_cost_usd // 0) | tostring)
    else empty end' 2>/dev/null
}

follower=""
# Killing only the subshell would leave its tail -F running forever.
stop_follower() {
  [[ -n "$follower" ]] || return 0
  pkill -P "$follower" 2>/dev/null || true
  kill "$follower" 2>/dev/null || true
  wait "$follower" 2>/dev/null || true
}
trap stop_follower EXIT
trap 'exit 130' INT TERM

current="$(newest)"
[[ -n "$current" ]] || { echo "loop-watch: no session in $logs yet" >&2; exit 1; }
echo "loop-watch: following new sessions in $logs (Ctrl+C to leave; the loop keeps running)"

following=""
while true; do
  if [[ "$current" != "$following" ]]; then
    stop_follower
    following="$current"
    echo "loop-watch: $following"
    (
      trap - EXIT INT TERM
      tail -n +1 -F "$following" | render
    ) &
    follower=$!
  fi
  sleep "$poll_s"
  current="$(newest)"
  [[ -n "$current" ]] || current="$following"
done
