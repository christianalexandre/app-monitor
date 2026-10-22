#!/usr/bin/env bash
# Usage: branch_check.sh [head-branch] [base-branch]
# Without arguments, checks the current branch (guidelines 03).
set -euo pipefail

head="${1:-$(git branch --show-current)}"
base="${2:-}"
types='feat|fix|refactor|perf|test|docs|build|ci|chore'

fail() { echo "branch-check: $1" >&2; exit 1; }

if [[ "$base" == "main" ]]; then
  [[ "$head" == "dev" || "$head" =~ ^release/[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || fail "PRs to main come only from dev or release/x.y.z (got '$head')."
  echo "branch-check: ok ($head -> main)"; exit 0
fi

if [[ -z "$base" && ( "$head" == "dev" || "$head" == "main" ) ]]; then
  echo "branch-check: ok ($head)"; exit 0
fi

[[ "$head" =~ ^($types)/[a-z0-9]+(-[a-z0-9]+){1,5}$ ]] \
  || fail "'$head' must be <type>/<kebab-slug>, type in {${types//|/, }}, 2-6 words."
echo "branch-check: ok ($head${base:+ -> $base})"
