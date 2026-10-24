#!/usr/bin/env bash
# Regenerates the swift-protobuf code of sdk/ios from protocol/proto (ADR-0039).
# Uses protoc and protoc-gen-swift built from the pinned swift-protobuf, so no
# system protoc is needed. Run after any change to protocol/proto.
set -euo pipefail
cd "$(dirname "$0")/.."

pkg=sdk/ios
out="$pkg/Sources/AppMonitor/Generated"

swift build --package-path "$pkg" -c release --product protoc
swift build --package-path "$pkg" -c release --product protoc-gen-swift
bin="$(swift build --package-path "$pkg" -c release --show-bin-path)"

rm -f "$out"/*.pb.swift
"$bin/protoc" -I protocol/proto \
  --plugin=protoc-gen-swift="$bin/protoc-gen-swift" \
  --swift_out="$out" \
  --swift_opt=Visibility=Internal \
  --swift_opt=FileNaming=PathToUnderscores \
  protocol/proto/appmonitor/v1/*.proto
# ADR-0048: no generated code, and no SwiftProtobuf import, in a release build;
# swift-format reads its ignore marker only before the first directive.
for f in "$out"/*.pb.swift; do
  { echo "// swift-format-ignore-file"; echo "#if DEBUG"; cat "$f"; echo "#endif"; } > "$f.tmp"
  mv "$f.tmp" "$f"
done
scripts/swift_proto_hash.sh > "$out/proto.sha256"
echo "swift-proto: regenerated $(ls "$out"/*.pb.swift | wc -l | tr -d ' ') files in $out"
