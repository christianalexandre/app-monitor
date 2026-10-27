#!/usr/bin/env bash
# ADR-0048: the debug and release builds of sdk/ios's AppMonitor and
# AppMonitorGRPC match the committed api/<module>.swiftinterface, release without
# SwiftProtobuf; every other source is wrapped in `#if DEBUG` after its leading
# `//` lines. Usage: swift_api_check.sh [--update] [SOURCES_DIR [GRPC_SOURCES_DIR]].
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

# $1 sources, then the files compiled in release too; everything else is debug only.
check_wrapped() {
  local dir="$1" fail=0 f name
  shift
  while IFS= read -r f; do
    name="$(basename "$f")"
    if [[ " $* " == *" $name "* ]]; then continue; fi
    if [[ "$(grep -v -m 1 '^//' "$f")" != "#if DEBUG" || "$(tail -n 1 "$f")" != "#endif" ]]; then
      echo "swift-api: $f is not wrapped in #if DEBUG ... #endif (ADR-0048)" >&2
      fail=1
    fi
  done < <(find "$dir" -name '*.swift' | sort)
  return $fail
}
fail=0
check_wrapped "$src" AppMonitor.swift ProtocolConstants.swift || fail=1
check_wrapped "$grpc_src" AppMonitorGRPCInterceptor.swift || fail=1
[[ $fail -eq 0 ]] || exit 1

modules="$pkg/.build/debug/Modules"
if [[ ! -e "$modules/SwiftProtobuf.swiftmodule" || ! -e "$modules/GRPCCore.swiftmodule" ]]; then
  swift build --package-path "$pkg" >/dev/null
fi

sdk="$(xcrun --sdk macosx --show-sdk-path)"

# Imports and header comments are not API; the shadowing of module and enum
# breaks the compiler's own verification (swiftlang/swift#56573).
# interface MODULE CONFIG SOURCES_DIR [swiftc flags...]; the module lands in $out/CONFIG.
interface() {
  local module="$1" config="$2" dir="$3" sources=() f
  shift 3
  while IFS= read -r f; do sources+=("$f"); done < <(find "$dir" -name '*.swift' | sort)
  mkdir -p "$out/$config"
  xcrun swiftc -parse-as-library -emit-module -sdk "$sdk" -target "$(uname -m)-apple-macos12" \
    -swift-version 6 -module-name "$module" -package-name appmonitor -enable-library-evolution \
    -no-verify-emitted-module-interface -suppress-warnings "$@" \
    -emit-module-path "$out/$config/$module.swiftmodule" \
    -emit-module-interface-path "$out/$config/$module.raw" "${sources[@]}"
  grep -v -e '^//' -e '^import ' "$out/$config/$module.raw" > "$out/$config/$module.swiftinterface"
}
interface AppMonitor debug "$src" -D DEBUG -I "$modules" -I "$pkg/Sources/AppMonitorConnections/include"
interface AppMonitor release "$src"
interface AppMonitorGRPC debug "$grpc_src" -D DEBUG -I "$out/debug" -I "$modules"
interface AppMonitorGRPC release "$grpc_src" -I "$modules"

for module in AppMonitor AppMonitorGRPC; do
  dump="$pkg/api/$module.swiftinterface"
  if [[ $update -eq 1 ]]; then
    mkdir -p "$(dirname "$dump")"
    cp "$out/debug/$module.swiftinterface" "$dump"
  fi
  for config in debug release; do
    if ! diff -u "$dump" "$out/$config/$module.swiftinterface" >&2; then
      echo "swift-api: the $config build's public API differs from $dump (ADR-0048);" \
        "align the release stubs, then run scripts/swift_api_check.sh --update" >&2
      fail=1
    fi
  done
done
[[ $fail -eq 0 ]] || exit 1
echo "swift-api: debug and release match $pkg/api/AppMonitor.swiftinterface and AppMonitorGRPC.swiftinterface"
