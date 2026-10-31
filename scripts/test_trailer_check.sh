#!/usr/bin/env bash
# Tests trailer_check.sh on a scratch repository; a release/x.y.z branch cut from dev is checked as M10 opens it.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")/build/test-trailer-check"
rm -rf "$repo" && mkdir -p "$repo" && cd "$repo" || exit 1
git init -q -b main && git config user.name t && git config user.email t@example.com
git commit -q --allow-empty -m "chore: start"

failures=0
check() { # name expected-exit base head
  out="$("$here/trailer_check.sh" "$3" "$4" 2>&1)"
  code=$?
  if [[ "$code" == "$2" ]]; then echo "  ok   $1"; else
    echo "  FAIL $1: expected exit $2, got $code: $out" >&2
    failures=$((failures + 1))
  fi
}

git switch -q -c dev
git commit -q --allow-empty -m "feat: protobuf" -m "Swift files generated with protoc."
check "prose naming a generator" 0 main dev
git switch -q -c release/1.0.0
check "release branch cut from dev" 0 main release/1.0.0
git commit -q --allow-empty -m "fix: one" -m "Co-authored-by: Someone <s@example.com>"
check "co-author trailer" 1 main release/1.0.0
git switch -q dev
git commit -q --allow-empty -m "fix: two" -m "🤖 Generated with [a tool](https://example.com)"
check "generated-with trailer" 1 main dev
cd "$here" && rm -rf "$repo"

if ((failures > 0)); then
  echo "trailer_check: $failures failure(s)" >&2
  exit 1
fi
echo "trailer_check: all tests passed"
