#!/usr/bin/env bash
# Swift gates of sdk/ios (ADR-0037, ADR-0039, ADR-0047, ADR-0048): generated code
# matches protocol/proto, swift-format lint, swift test, the debug and release
# public API (ADR-0048), the simulator tests.
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
swift format lint --strict --recursive --configuration "$pkg/.swift-format" \
  "$pkg/Package.swift" "$pkg/Sources" "$pkg/Tests" "$pkg/TestHost"
swift test --package-path "$pkg"
scripts/swift_api_check.sh
scripts/test_swift_api_check.sh
if command -v xcodegen >/dev/null 2>&1; then
  scripts/swift_sim_check.sh
else
  echo "swift-sim: SKIPPED (needs 'brew install xcodegen'; ADR-0047)"
fi
