---
id: ADR-0038
title: Exchanges carry the capture layer that produced them
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0038 — Exchanges carry the capture layer that produced them

## Context

ADR-0004 splits iOS capture into four layers (full content from URLSession,
library adapters, connection metadata, desktop proxy) and requires the
desktop to show which layer produced each entry. Android's OkHttp
interceptor (ADR-0025) is an adapter: the app adds it to a library it owns.
ADR-0008 forbids renumbering fields; `appmonitor.v1` stays.

Evidence, checked on 2026-10-23:

- Proto3 enums are open: a value the reader does not know is still a valid
  field value (protobuf.dev, "Enum Behavior").
- Wire 7.1.0 decodes an unknown value of a proto3 enum field to the field's
  default: `RoundTripTest.captureLayerFromANewerPeerIsUnspecified` (field 8,
  value 9 → `CAPTURE_LAYER_UNSPECIFIED`) passes on JVM, Android host and iOS
  simulator.
- Every event after `RequestStarted` already links to it by `request_id`
  (`TrafficState.update`, `WebSocketFrame.request_id`).

## Options

1. **`CaptureLayer capture_layer = 8` on `RequestStarted` only** — one enum,
   one field; the other events inherit it through `request_id`. An exchange
   seen without its `RequestStarted` shows "Unknown layer".
2. **The same field on every network message** — each event stands alone,
   but four fields that must agree and four times the bytes for no reader
   gain.
3. **A free `string capture_source`** ("okhttp", "URLSession") — more
   precise, but the desktop cannot group or filter by layer, and nothing
   keeps senders to one spelling.

## Decision

Option 1. `CaptureLayer` has `UNSPECIFIED = 0` (older senders), `PLATFORM`,
`ADAPTER`, `CONNECTION` and `PROXY`, declared now so a desktop built today
labels them all. The OkHttp module sends `ADAPTER`; the iOS SDK's URLSession
capture (M2.7) will send `PLATFORM`.

## Consequences

- The desktop detail caption shows "<Layer> layer"; an older SDK or an
  unknown value shows "Unknown layer".
- `request_started.bin` was regenerated with `ADAPTER` (two bytes added);
  the Swift contract tests (M2.3) decode it.
- Which library produced an adapter entry is not on the wire; a source
  string can be added later as a new field without breaking this one.
