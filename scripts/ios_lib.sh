# Sourced by scripts/swift_sim_check.sh, scripts/ios_sample_check.sh and scripts/kmp_sample_check.sh
# (ADR-0047, ADR-0155).
# The caller sets `tag`, the prefix of every line.

ios_fail() {
  echo "$tag: $*" >&2
  exit 1
}

for tool in xcodebuild xcrun xcodegen; do
  command -v "$tool" >/dev/null 2>&1 \
    || ios_fail "$tool not found (needs macOS with Xcode and 'brew install xcodegen'; ADR-0047)"
done

# Prints SIM_DEVICE, else the name of the first available iPhone simulator; fails when there is none.
ios_device() {
  local device="${SIM_DEVICE:-$(xcrun simctl list devices available | scripts/sim_list.sh | head -1 | cut -d' ' -f2-)}"
  [[ -n "$device" ]] || ios_fail "no available iPhone simulator (install one in Xcode > Settings > Components)"
  echo "$device"
}

# $1 configuration, $2 the built .app: both local network keys in Debug, none in Release.
check_network_keys() {
  local keys=0 key
  for key in NSLocalNetworkUsageDescription NSBonjourServices; do
    if plutil -extract "$key" raw -o /dev/null "$2/Info.plist" 2>/dev/null; then keys=$((keys + 1)); fi
  done
  if [[ "$1" == Debug && $keys -ne 2 ]] || [[ "$1" == Release && $keys -ne 0 ]]; then
    plutil -p "$2/Info.plist" >&2
    ios_fail "$1 $(basename "$2") has $keys of the 2 local network keys (ADR-0155)"
  fi
}

# $1 a built .app: its executable, the dylibs beside it (the debug one holds the code) and embedded frameworks.
app_binaries() {
  find "$1" -maxdepth 1 -type f \( -name "$(basename "$1" .app)" -o -name '*.dylib' \)
  find "$1/Frameworks" -maxdepth 2 -type f -path '*.framework/*' -perm -u+x 2>/dev/null || true
}
