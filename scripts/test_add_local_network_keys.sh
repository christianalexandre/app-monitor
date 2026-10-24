#!/usr/bin/env bash
# Tests of sdk/ios/scripts/add_local_network_keys.sh (ADR-0049) on binary plists
# under build/: which configurations get the keys, the app's own keys kept, a
# second run changes nothing. macOS only (plutil), run by make swift.
set -euo pipefail
cd "$(dirname "$0")/.."

work=build/plist-keys-test
script=sdk/ios/scripts/add_local_network_keys.sh
fails=0

# Prints a JSON plist with its keys sorted, so key order never fails a case.
norm() {
  printf '%s' "$1" | plutil -p -
}

# $1 configuration, $2 JSON of the plist before; prints the plist after.
run() {
  rm -rf "$work"
  mkdir -p "$work"
  printf '%s' "$2" >"$work/Info.plist"
  plutil -convert binary1 "$work/Info.plist"
  CONFIGURATION="$1" TARGET_BUILD_DIR="$work" INFOPLIST_PATH=Info.plist "$script" >/dev/null
  if [[ $# -gt 2 ]]; then
    CONFIGURATION="$1" TARGET_BUILD_DIR="$work" INFOPLIST_PATH=Info.plist "$script" >/dev/null
  fi
  plutil -p "$work/Info.plist"
}

expect() {
  local label="$1" want got="$3"
  want="$(norm "$2")"
  if [[ "$got" == "$want" ]]; then
    echo "ok   $label"
  else
    echo "FAIL $label"
    echo "  want $want"
    echo "  got  $got"
    fails=$((fails + 1))
  fi
}

usage="AppMonitor sends the network traffic of this debug build to the AppMonitor desktop app on your local network."
keys="{\"NSBonjourServices\":[\"_appmonitor._tcp\"],\"NSLocalNetworkUsageDescription\":\"$usage\"}"

expect "Release left as is" '{"CFBundleName":"App"}' "$(run Release '{"CFBundleName":"App"}')"
expect "Staging left as is" '{}' "$(run Staging '{}')"
expect "Debug gets both keys" "$keys" "$(run Debug '{}')"
expect "StagingDebug gets both keys" "$keys" "$(run StagingDebug '{}')"
expect "a second run changes nothing" "$keys" "$(run Debug '{}' twice)"
expect "the app's own keys kept, the service appended" \
  '{"NSBonjourServices":["_http._tcp","_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"Mine"}' \
  "$(run Debug '{"NSBonjourServices":["_http._tcp"],"NSLocalNetworkUsageDescription":"Mine"}')"
expect "the service not added twice" '{"NSBonjourServices":["_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"Mine"}' \
  "$(run Debug '{"NSBonjourServices":["_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"Mine"}')"
expect "the usage string from APPMONITOR_LOCAL_NETWORK_USAGE" \
  '{"NSBonjourServices":["_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"It'"'"'s \"ours\""}' \
  "$(APPMONITOR_LOCAL_NETWORK_USAGE="It's \"ours\"" run Debug '{}')"

if [[ $fails -gt 0 ]]; then
  echo "test_add_local_network_keys: $fails failed" >&2
  exit 1
fi
