---
id: ADR-0008
title: Protocol schema layout and golden fixtures
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0008 — Protocol schema layout and golden fixtures

## Context

ADR-0001 chose Protobuf with an `Envelope { channel, type, payload,
timestamp }`, Wire for Kotlin and swift-protobuf for Swift. Guidelines 04
require contract fixtures for protocol changes. This ADR fixes where the
schema lives, how it is versioned and how fixtures are produced and checked.
Wire 7.1.0 (Maven Central, 2026-09-28) generates Kotlin for every KMP target
from `commonMain`.

## Options

1. **Schema inside `src/commonMain/proto`** — Wire's KMP default; the Swift
   build would reach into a Kotlin source set.
2. **Schema in `protocol/proto/`** — neutral path for both toolchains; Wire
   needs an explicit `sourcePath`.
3. **Fixtures as hex in test code** — no files; Swift cannot share them.
4. **Fixtures as binary files** — one encoded `Envelope` per file, shared by
   both languages.

## Decision

Options 2 and 4. Protobuf package `appmonitor.v1` (generated Kotlin package
is the same); a breaking change means a new `v2` package, never a reused
field number. One file per channel: `envelope.proto`, `core.proto`
(handshake), `network.proto`. `Envelope.timestamp_ms` is the event time, so
channel messages carry no timestamps. `Envelope.type` is the message name
inside its channel. Fixtures in `protocol/fixtures/*.bin` are encoded from
the samples in `commonTest` and verified byte-for-byte by a JVM test;
`-PupdateFixtures` rewrites them.

## Consequences

- The Swift SDK (roadmap M2) decodes the same files to prove the contract.
- Regenerating fixtures is a deliberate act and shows up as a binary diff in
  the PR.
- Fixture files are read on the JVM only; Android and iOS run the same
  round-trip tests on the shared Wire code.
