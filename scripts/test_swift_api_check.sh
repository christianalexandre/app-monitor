#!/usr/bin/env bash
# Tests of swift_api_check.sh (ADR-0048, ADR-0155) on copies of the iOS SDK and
# gRPC adapter sources under build/: unchanged sources pass; a new public member
# fails. macOS with swift only, run by make swift.
set -euo pipefail
cd "$(dirname "$0")/.."

work=build/swift-api-test
fails=0

fresh_copy() {
  rm -rf "$work"
  mkdir -p "$work"
  cp -R sdk/ios/Sources/AppMonitor "$work/src"
  cp -R sdk/ios/Sources/AppMonitorGRPC "$work/grpc"
}

expect() {
  local want="$1" label="$2" got=0
  scripts/swift_api_check.sh "$work/src" "$work/grpc" >"$work/log" 2>&1 || got=1
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
printf '\nextension AppMonitor {\n    public static func added() {}\n}\n' >> "$work/src/AppMonitor.swift"
expect fail "new public member"

fresh_copy
printf '\nextension AppMonitorGRPCInterceptor {\n    public func added() {}\n}\n' \
  >> "$work/grpc/AppMonitorGRPCInterceptor.swift"
expect fail "gRPC adapter new public member"

rm -rf "$work"
[[ $fails -eq 0 ]] || exit 1
