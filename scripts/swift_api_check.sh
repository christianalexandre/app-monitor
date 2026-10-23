#!/usr/bin/env bash
# ADR-0048: the debug and release builds of sdk/ios match the committed
# api/AppMonitor.swiftinterface, release without SwiftProtobuf; every other
# source is wrapped in `#if DEBUG` after its leading `//` lines.
# Usage: swift_api_check.sh [--update] [SOURCES_DIR]; macOS with swift only.
set -euo pipefail
cd "$(dirname "$0")/.."

update=0
if [[ "${1:-}" == "--update" ]]; then
  update=1
  shift
fi
src="${1:-sdk/ios/Sources/AppMonitor}"
pkg=sdk/ios
dump="$pkg/api/AppMonitor.swiftinterface"
out=build/swift-api
mkdir -p "$out"

# Compiled in release too; everything else is debug only.
release_files=("AppMonitor.swift" "ProtocolConstants.swift")
fail=0
while IFS= read -r f; do
  name="$(basename "$f")"
  if [[ " ${release_files[*]} " == *" $name "* ]]; then continue; fi
  if [[ "$(grep -v -m 1 '^//' "$f")" != "#if DEBUG" || "$(tail -n 1 "$f")" != "#endif" ]]; then
    echo "swift-api: $f is not wrapped in #if DEBUG ... #endif (ADR-0048)" >&2
    fail=1
  fi
done < <(find "$src" -name '*.swift' | sort)
[[ $fail -eq 0 ]] || exit 1

modules="$pkg/.build/debug/Modules"
if [[ ! -e "$modules/SwiftProtobuf.swiftmodule" ]]; then
  swift build --package-path "$pkg" >/dev/null
fi

sdk="$(xcrun --sdk macosx --show-sdk-path)"
sources=()
while IFS= read -r f; do sources+=("$f"); done < <(find "$src" -name '*.swift' | sort)

# Imports and header comments are not API; the shadowing of module and enum
# breaks the compiler's own verification (swiftlang/swift#56573).
interface() {
  local config="$1"
  shift
  xcrun swiftc -parse-as-library -typecheck -sdk "$sdk" -target "$(uname -m)-apple-macos12" \
    -swift-version 6 -module-name AppMonitor -enable-library-evolution -no-verify-emitted-module-interface \
    -suppress-warnings "$@" -emit-module-interface-path "$out/$config.raw" "${sources[@]}"
  grep -v -e '^//' -e '^import ' "$out/$config.raw" > "$out/$config.swiftinterface"
}
interface debug -D DEBUG -I "$modules"
interface release

if [[ $update -eq 1 ]]; then
  mkdir -p "$(dirname "$dump")"
  cp "$out/debug.swiftinterface" "$dump"
fi
for config in debug release; do
  if ! diff -u "$dump" "$out/$config.swiftinterface" >&2; then
    echo "swift-api: the $config build's public API differs from $dump (ADR-0048);" \
      "align the release stubs, then run scripts/swift_api_check.sh --update" >&2
    fail=1
  fi
done
[[ $fail -eq 0 ]] || exit 1
echo "swift-api: debug and release match $dump"
