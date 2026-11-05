#!/usr/bin/env bash
# Regenerates sdk/ios's protobuf types from protocol/proto with scripts/swift_proto_gen.py,
# no protoc and no SwiftProtobuf (ADR-0160). Run after any change to protocol/proto.
set -euo pipefail
cd "$(dirname "$0")/.."

out=sdk/ios/Sources/AppMonitor/Generated
rm -f "$out"/*.pb.swift
python3 -I scripts/swift_proto_gen.py protocol/proto/appmonitor/v1 "$out"
scripts/swift_proto_hash.sh > "$out/proto.sha256"
echo "swift-proto: regenerated $(ls "$out"/*.pb.swift | wc -l | tr -d ' ') files in $out"
