#!/usr/bin/env bash
# Tests of sdk/ios/scripts/add_local_network_keys.sh (ADR-0155) on binary plists
# under build/: which builds get the keys or fail, the app's own keys kept, a
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

# $1 "EXCLUDED_SOURCE_FILE_NAMES|SWIFT_OPTIMIZATION_LEVEL", $2 JSON of the plist before;
# prints the plist after, or "failed" when the script exits non-zero.
run() {
  local excluded="${1%%|*}" optimization="${1#*|}" i
  rm -rf "$work"
  mkdir -p "$work"
  printf '%s' "$2" >"$work/Info.plist"
  plutil -convert binary1 "$work/Info.plist"
  for i in $(seq "${3:-1}"); do
    if ! CONFIGURATION=Any EXCLUDED_SOURCE_FILE_NAMES="$excluded" SWIFT_OPTIMIZATION_LEVEL="$optimization" \
      TARGET_BUILD_DIR="$work" INFOPLIST_PATH=Info.plist "$script" >/dev/null; then
      echo failed
      return
    fi
  done
  plutil -p "$work/Info.plist"
}

# The README carries the script inline (ADR-0155): its block must be the file, line for line.
readme_script() {
  awk '/^ *```sh appmonitor-run-script$/ {on = 1; next} on && /^ *```$/ {exit} on {sub(/^   /, ""); print}' README.md
}
if diff -u <(cat "$script") <(readme_script) >/dev/null; then
  echo "ok   README block is the script"
else
  echo "FAIL README block differs from $script"
  fails=$((fails + 1))
fi

expect() {
  local label="$1" want="$2" got="$3"
  [[ "$want" == failed ]] || want="$(norm "$2")"
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

expect "excluded AppMonitor* left as is" '{"CFBundleName":"App"}' "$(run 'AppMonitor*|-O' '{"CFBundleName":"App"}')"
expect "excluded among other patterns left as is" '{}' "$(run 'Mock* AppMonitor.o|-O' '{}')"
expect "AppMonitor.framework is no exclusion: optimized fails" 'failed' "$(run 'AppMonitor.framework|-O' '{}')"
expect "excluded at -Onone left as is" '{}' "$(run '*AppMonitor*|-Onone' '{}')"
expect "a bare AppMonitor pattern is no exclusion: optimized fails" 'failed' "$(run 'AppMonitor|-O' '{}')"
expect "linked and optimized fails" 'failed' "$(run '|-O' '{}')"
expect "linked and -Osize fails" 'failed' "$(run '|-Osize' '{}')"
expect "linked and optimized kept with APPMONITOR_ALLOW_OPTIMIZED" "$keys" \
  "$(APPMONITOR_ALLOW_OPTIMIZED=YES run '|-O' '{}')"
expect "linked at -Onone gets both keys" "$keys" "$(run '|-Onone' '{}')"
expect "a second run changes nothing" "$keys" "$(run '|-Onone' '{}' 2)"
expect "the app's own keys kept, the service appended" \
  '{"NSBonjourServices":["_http._tcp","_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"Mine"}' \
  "$(run '|-Onone' '{"NSBonjourServices":["_http._tcp"],"NSLocalNetworkUsageDescription":"Mine"}')"
expect "the service not added twice" '{"NSBonjourServices":["_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"Mine"}' \
  "$(run '|-Onone' '{"NSBonjourServices":["_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"Mine"}')"
expect "the usage string from APPMONITOR_LOCAL_NETWORK_USAGE" \
  '{"NSBonjourServices":["_appmonitor._tcp"],"NSLocalNetworkUsageDescription":"It'"'"'s \"ours\""}' \
  "$(APPMONITOR_LOCAL_NETWORK_USAGE="It's \"ours\"" run '|-Onone' '{}')"

if [[ $fails -gt 0 ]]; then
  echo "test_add_local_network_keys: $fails failed" >&2
  exit 1
fi
