#!/usr/bin/env bash
# ADR-0048, ADR-0155: the public API of sdk/ios's AppMonitor and AppMonitorGRPC
# matches the committed api/<module>.swiftinterface; release builds exclude the
# whole SDK, so there is one build. Usage: swift_api_check.sh [--update] [SOURCES_DIR [GRPC_SOURCES_DIR]].
set -euo pipefail
cd "$(dirname "$0")/.."

update=0
if [[ "${1:-}" == "--update" ]]; then
  update=1
  shift
fi
src="${1:-sdk/ios/Sources/AppMonitor}"
grpc_src="${2:-sdk/ios/Sources/AppMonitorGRPC}"
pkg=sdk/ios
out=build/swift-api
mkdir -p "$out"

modules="$pkg/.build/debug/Modules"
if [[ ! -e "$modules/SwiftProtobuf.swiftmodule" || ! -e "$modules/GRPCCore.swiftmodule" ]]; then
  swift build --package-path "$pkg" >/dev/null
fi

sdk="$(xcrun --sdk macosx --show-sdk-path)"

# Imports and header comments are not API; the shadowing of module and enum
# breaks the compiler's own verification (swiftlang/swift#56573).
# interface MODULE SOURCES_DIR [swiftc flags...]; the module lands in $out.
interface() {
  local module="$1" dir="$2" sources=() f
  shift 2
  while IFS= read -r f; do sources+=("$f"); done < <(find "$dir" -name '*.swift' | sort)
  xcrun swiftc -parse-as-library -emit-module -sdk "$sdk" -target "$(uname -m)-apple-macos12" \
    -swift-version 6 -module-name "$module" -package-name appmonitor -enable-library-evolution \
    -no-verify-emitted-module-interface -suppress-warnings "$@" \
    -emit-module-path "$out/$module.swiftmodule" \
    -emit-module-interface-path "$out/$module.raw" "${sources[@]}"
  grep -v -e '^//' -e '^import ' "$out/$module.raw" > "$out/$module.swiftinterface"
}
interface AppMonitor "$src" -I "$modules" -I "$pkg/Sources/AppMonitorConnections/include"
interface AppMonitorGRPC "$grpc_src" -I "$out" -I "$modules"

fail=0
for module in AppMonitor AppMonitorGRPC; do
  dump="$pkg/api/$module.swiftinterface"
  if [[ $update -eq 1 ]]; then
    mkdir -p "$(dirname "$dump")"
    cp "$out/$module.swiftinterface" "$dump"
  fi
  if ! diff -u "$dump" "$out/$module.swiftinterface" >&2; then
    echo "swift-api: the public API differs from $dump; after a deliberate change" \
      "run scripts/swift_api_check.sh --update" >&2
    fail=1
  fi
done
[[ $fail -eq 0 ]] || exit 1
echo "swift-api: public API matches $pkg/api/AppMonitor.swiftinterface and AppMonitorGRPC.swiftinterface"
