#!/usr/bin/env bash
# Tests of swift_api_check.sh (ADR-0048) on copies of the iOS SDK sources under
# build/: unchanged sources pass; a public member on one side, or a file
# outside `#if DEBUG`, fails. macOS with swift only, run by make swift.
set -euo pipefail
cd "$(dirname "$0")/.."

work=build/swift-api-test
fails=0

fresh_copy() {
  rm -rf "$work"
  mkdir -p "$work"
  cp -R sdk/ios/Sources/AppMonitor "$work/src"
}

expect() {
  local want="$1" label="$2" got=0
  scripts/swift_api_check.sh "$work/src" >"$work/log" 2>&1 || got=1
  if [[ "$want" == "pass" && $got -eq 0 ]] || [[ "$want" == "fail" && $got -eq 1 ]]; then
    echo "ok   $label"
  else
    echo "FAIL $label (expected $want)"
    cat "$work/log"
    fails=$((fails + 1))
  fi
}

fresh_copy
expect pass "unchanged sources"

fresh_copy
printf '\n#if DEBUG\nextension AppMonitor {\n    public static func debugOnly() {}\n}\n#endif\n' \
  >> "$work/src/AppMonitor.swift"
expect fail "public member in the debug build only"

fresh_copy
printf '\n#if !DEBUG\nextension AppMonitor {\n    public static func releaseOnly() {}\n}\n#endif\n' \
  >> "$work/src/AppMonitor.swift"
expect fail "public member in the release build only"

fresh_copy
printf 'let leaked = 1\n' > "$work/src/Leaked.swift"
expect fail "source outside #if DEBUG"

rm -rf "$work"
[[ $fails -eq 0 ]] || exit 1
