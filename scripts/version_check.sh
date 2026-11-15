#!/usr/bin/env bash
# One version for every SDK (ADR-0054, ADR-0216): VERSION_NAME, the Kotlin SDK's Hello and
# the Swift SDK's Hello agree; with --release TAG, the tag is that version and has no suffix.
# The Gradle plugin's SDK version is generated from VERSION_NAME (ADR-0157).
set -euo pipefail
root="${VERSION_CHECK_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"

tag=""
case "${1:-}" in
  "") ;;
  --release)
    tag="${2:-}"
    [[ -n "$tag" ]] || { echo "usage: $0 [--release TAG]" >&2; exit 2; }
    ;;
  *) echo "usage: $0 [--release TAG]" >&2; exit 2 ;;
esac

read_one() { # file, sed expression, label
  local value
  value="$(sed -n "$2" "$root/$1" 2>/dev/null | head -1)"
  [[ -n "$value" ]] || { echo "version-check: no $3 in $1" >&2; exit 1; }
  printf '%s' "$value"
}
version="$(read_one gradle.properties 's/^VERSION_NAME=//p' VERSION_NAME)"
kotlin="$(read_one sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/Platform.kt \
  's/^internal const val SDK_VERSION: String = "\(.*\)"$/\1/p' SDK_VERSION)"
swift="$(read_one sdk/ios/Sources/AppMonitor/Device.swift 's/^let sdkVersion = "\(.*\)"$/\1/p' sdkVersion)"

fail=0
[[ "$kotlin" == "$version" ]] || { echo "version-check: sdk/core SDK_VERSION is $kotlin, VERSION_NAME is $version" >&2; fail=1; }
[[ "$swift" == "$version" ]] || { echo "version-check: sdk/ios sdkVersion is $swift, VERSION_NAME is $version" >&2; fail=1; }
if [[ -n "$tag" ]]; then
  [[ "$tag" == "$version" ]] || { echo "version-check: tag $tag is not VERSION_NAME $version" >&2; fail=1; }
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    { echo "version-check: $version is not X.Y.Z; a -SNAPSHOT or -dev version never publishes" >&2; fail=1; }
fi
[[ $fail -eq 0 ]] || exit 1
echo "version-check: ok ($version${tag:+, tag $tag})"
