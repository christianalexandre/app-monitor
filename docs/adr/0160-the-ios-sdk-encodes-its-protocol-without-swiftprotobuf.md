---
id: ADR-0160
title: The iOS SDK encodes its protocol with generated code of its own, without SwiftProtobuf
status: Accepted
date: 2026-10-29
superseded_by:
---

# ADR-0160 — The iOS SDK encodes its protocol with generated code of its own, without SwiftProtobuf

## Context

ADR-0039 generated the iOS SDK's protocol types with protoc and
protoc-gen-swift, so `AppMonitor` depended on swift-protobuf, pinned
`exact: "1.39.0"`. Two costs showed up once apps tried it:

- An app that already uses SwiftProtobuf at another version cannot resolve
  both. SwiftPM finds no version that fits `exact` and theirs, so the
  package cannot even be added. That contradicts the owner's plug and play
  goal (2026-10-28).
- With the SDK excluded from release builds (ADR-0155), the SwiftProtobuf
  runtime stayed: 14,774 symbols in a probe `Prod` app (DEBT-0040).

The protocol is small: 22 messages and 4 enums in four files. It uses only
proto3 scalars, `string`, `bytes`, enums, nested messages, `repeated`,
`optional` and one `oneof`. `protocol/fixtures/` holds every message as
Kotlin's Wire encodes it, and `FixtureTests` checks decode and re-encode
byte for byte. The gRPC adapter (`AppMonitorGRPC`) serializes the app's own
messages, so it needs SwiftProtobuf, and an app using it already has it.

## Options

1. **Keep SwiftProtobuf with a version range.** This fixes resolution, but
   the runtime stays in release builds and the SDK keeps a dependency.
2. **Hand-written encoding of each message.** No dependency, but 22
   messages kept equal to the schema by hand.
3. **A generator of our own.** `scripts/swift_proto_gen.py` reads the
   proto3 subset above and stops on anything else (maps, nested messages,
   other scalar types). It writes structs with protoc-gen-swift's names and
   API: `Appmonitor_V1_*`, `requestID`, `.android`, `has`/`clear` for
   `optional` and message fields, `OneOf_*`, `.with {}`,
   `serializedBytes()`, `init(serializedBytes:)`, `unknownFields`. They run
   on `ProtoWire.swift`, a hand-written writer and reader: fields in number
   order, proto3 defaults left out, negative `int32` sign-extended, unknown
   fields and enum values kept, malformed input thrown.

## Decision

Option 3. `AppMonitor` has no package dependency. `AppMonitorGRPC` keeps
SwiftProtobuf, `from: "1.28.0"`, for the app's messages; SwiftPM resolves it
only for apps that use that product (target-based resolution, SE-0226).
From ADR-0039, the following stays: `internal` types in
`Sources/AppMonitor/Generated/`, `make swift-proto` writing them and the
schema's SHA-256, and `make swift` failing on a stale hash before
`swift format lint --strict` and `swift test`, `SKIPPED` off macOS. It adds a
check that the committed files are the generator's output.

## Consequences

- `make swift-proto` needs only `python3`, with no protoc build.
- The SDK's sources and tests changed only where they named
  `SwiftProtobuf.Message` (`ProtoMessage` now). `ProtoWireTests` covers what
  the fixtures do not.
- `make ios-sample` fails if the Debug or the Release app holds a
  `SwiftProtobuf` symbol. DEBT-0040 is paid.
- A new protobuf feature in the schema stops `make swift-proto` until the
  generator writes it.
- Supersedes ADR-0039.
