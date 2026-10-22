#!/usr/bin/env bash
# Follows the newest loop session live: assistant text and tool calls.
set -euo pipefail
logs="$(cd "$(dirname "$0")/.." && pwd)/.loop"

latest="$(ls -t "$logs"/*.jsonl 2>/dev/null | head -1 || true)"
[[ -n "$latest" ]] || { echo "loop-watch: no session in $logs yet" >&2; exit 1; }
echo "loop-watch: $latest (Ctrl+C to leave; the loop keeps running)"

tail -n +1 -F "$latest" | jq --unbuffered -r '
  if .type == "assistant" then
    .message.content[]
    | if .type == "text" then "💬 " + .text
      elif .type == "tool_use" then
        "🔧 " + .name + "  " + ((.input.command // .input.file_path // .input.pattern // .input.skill // "") | tostring | .[0:160])
      else empty end
  elif .type == "result" then "✅ done — $" + ((.total_cost_usd // 0) | tostring)
  else empty end' 2>/dev/null
