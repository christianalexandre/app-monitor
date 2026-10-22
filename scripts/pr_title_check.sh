#!/usr/bin/env bash
# Usage: pr_title_check.sh "<title>"  (Conventional Commits, guidelines 03)
set -euo pipefail

title="$1"
re='^(feat|fix|refactor|perf|test|docs|build|ci|chore|release)(\([a-z0-9-]+\))?!?: [a-z0-9].*[^.]$'

[[ "$title" =~ $re ]] || { echo "pr-title-check: '$title' is not 'type(scope): lowercase summary'." >&2; exit 1; }
(( ${#title} <= 72 )) || { echo "pr-title-check: title has ${#title} chars, max 72." >&2; exit 1; }
echo "pr-title-check: ok"
