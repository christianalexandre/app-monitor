#!/usr/bin/env bash
# Usage: trailer_check.sh <base> <head>  (no attribution trailers in base..head, guidelines 03)
# Matches the trailer's form at a line start, so prose such as "files generated with protoc" passes.
set -euo pipefail

if found="$(git log --format=%B "$1..$2" | grep -iE '^co-authored-by:|^[^[:alnum:]]*generated with ')"; then
  echo "$found" >&2
  echo "trailer-check: commits in $1..$2 carry attribution trailers (guidelines 03)." >&2
  exit 1
fi
echo "trailer-check: ok ($1..$2)"
