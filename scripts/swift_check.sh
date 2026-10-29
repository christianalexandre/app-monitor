#!/usr/bin/env bash
# Host Swift gates of sdk/ios (ADR-0037, ADR-0076, ADR-0155, ADR-0160): generated code matches
# protocol/proto and the generator, swift-format lint, swift test, the public API, the Run
# Script and its README copy. Simulator and samples: make check-full.
# macOS with swift only; elsewhere prints one skip line and passes.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "$(uname -s)" != "Darwin" ]] || ! command -v swift >/dev/null 2>&1; then
  echo "swift: SKIPPED (needs macOS with swift on the PATH; ADR-0037)"
  exit 0
fi

pkg=sdk/ios
if ! diff -q <(scripts/swift_proto_hash.sh) "$pkg/Sources/AppMonitor/Generated/proto.sha256" >/dev/null; then
  echo "swift: protocol/proto changed since the Swift code was generated; run make swift-proto" >&2
  exit 1
fi
# ADR-0160: the committed types are the generator's output, not edited by hand.
fresh="$(mktemp -d)"
python3 -I scripts/swift_proto_gen.py protocol/proto/appmonitor/v1 "$fresh"
if ! diff -r -x proto.sha256 "$fresh" "$pkg/Sources/AppMonitor/Generated" >&2; then
  echo "swift: Generated/ differs from scripts/swift_proto_gen.py's output; run make swift-proto" >&2
  exit 1
fi
rm -rf "$fresh"
swift format lint --strict --recursive --configuration "$pkg/.swift-format" \
  Package.swift "$pkg/Package.swift" "$pkg/Sources" "$pkg/Tests" "$pkg/TestHost" samples/ios samples/kmp/iosApp
swift test --package-path "$pkg"
scripts/swift_api_check.sh
scripts/test_swift_api_check.sh
scripts/test_add_local_network_keys.sh
