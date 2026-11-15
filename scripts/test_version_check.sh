#!/usr/bin/env bash
# Tests scripts/version_check.sh on a scratch tree (ADR-0216).
set -euo pipefail
cd "$(dirname "$0")/.."
check="$PWD/scripts/version_check.sh"
tree="$PWD/build/test-version-check"
failures=0

make_tree() { # VERSION_NAME, Kotlin SDK_VERSION, Swift sdkVersion
  rm -rf "$tree"
  mkdir -p "$tree/sdk/core/src/commonMain/kotlin/appmonitor/sdk/core" "$tree/sdk/ios/Sources/AppMonitor"
  printf 'org.gradle.caching=true\nVERSION_NAME=%s\n' "$1" >"$tree/gradle.properties"
  printf 'package appmonitor.sdk.core\n\ninternal const val SDK_VERSION: String = "%s"\n' "$2" \
    >"$tree/sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/Platform.kt"
  printf 'import Foundation\n\nlet sdkVersion = "%s"\n' "$3" >"$tree/sdk/ios/Sources/AppMonitor/Device.swift"
}

expect() { # name, wanted exit code, args...
  local name="$1" want="$2" got=0
  shift 2
  VERSION_CHECK_ROOT="$tree" "$check" "$@" >/dev/null 2>&1 || got=$?
  if [[ "$got" == "$want" ]]; then
    echo "ok   $name"
  else
    echo "FAIL $name: exit $got, wanted $want"
    failures=$((failures + 1))
  fi
}

make_tree 0.2.0-SNAPSHOT 0.2.0-SNAPSHOT 0.2.0-SNAPSHOT
expect "a snapshot on dev agrees" 0
expect "a snapshot refused for a release" 1 --release 0.2.0-SNAPSHOT
make_tree 0.2.0 0.2.0 0.2.0
expect "a matching tag" 0 --release 0.2.0
expect "a mismatching tag" 1 --release 0.2.1
make_tree 0.2.0-dev 0.2.0-dev 0.2.0-dev
expect "a -dev version refused for a release" 1 --release 0.2.0-dev
make_tree 0.2.0 0.1.0 0.2.0
expect "the Kotlin SDK behind" 1
make_tree 0.2.0 0.2.0 0.1.0
expect "the Swift SDK behind" 1
expect "a release with no tag" 2 --release
make_tree 0.2.0 0.2.0 0.2.0
expect "the real tree agrees" 0
rm -rf "$tree"
VERSION_CHECK_ROOT="" "$check" >/dev/null || { echo "FAIL the repository's versions disagree"; failures=$((failures + 1)); }
[[ $failures -eq 0 ]] || exit 1
