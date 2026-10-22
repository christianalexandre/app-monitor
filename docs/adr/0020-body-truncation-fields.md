---
id: ADR-0020
title: Bodies carry a truncation flag and their original size
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0020 — Bodies carry a truncation flag and their original size

## Context

ADR-0018 bounds each captured body to `maxBodyBytes` (1 MiB by default) and
sends bodies the SDK cannot read (one-shot, duplex) "empty with a marker".
The desktop must tell a whole body from a cut or unread one, and show how
big the original was when the SDK knows it (a `Content-Length`, or a body
read to its end). ADR-0008 forbids renumbering fields; `appmonitor.v1`
stays.

Evidence, checked on 2026-10-22:

- Proto3 scalars have no presence: an `int64` set to 0 is indistinguishable
  from an unset one; `optional` adds explicit presence (protobuf.dev,
  "Field Presence").
- Wire 7.1.0 (`gradle/libs.versions.toml`) generates `optional int64` as a
  nullable `Long?` (generated `ResponseReceived.kt` in `protocol/build`), and
  `RoundTripTest.zeroBodySizeIsKeptApartFromUnknown` passes on JVM, Android
  host and iOS simulator.

## Options

1. **`bool body_truncated` + `optional int64 body_size`** on `RequestStarted`
   and `ResponseReceived`. An unread body is `body_truncated = true` with an
   empty `body`: the SDK sent 0 of its bytes. An unknown size is absent, a
   known empty body is 0. Older peers decode as "whole, size unknown".
2. **An enum `BodyCapture { WHOLE, TRUNCATED, UNREAD }` + `int64 body_size`**
   — names the unread case explicitly, but a third state the desktop renders
   exactly like a cut at 0 bytes, and 0 still means "unknown".
3. **A nested `Body { bytes data; bool truncated; optional int64 size }`** —
   groups the three, but moves `body` to a new field number and breaks every
   M0 fixture for no reader gain.

## Decision

Option 1: `RequestStarted` fields 6 and 7, `ResponseReceived` fields 5 and 6.
`body_size` is the original size in bytes when the sender knows it, whether
or not the body was cut.

## Consequences

- The desktop shows "Truncated: N of M bytes shown" or "… original size
  unknown" above the body; an unread body shows only that note.
- M1.3 derives the server's `maxFrameSize` from the body limit; M1.7 fills
  both fields from OkHttp; the iOS SDK (M2) fills them the same way.
- `request_started.bin` and `response_received.bin` were regenerated; the
  response sample is the truncated case.
