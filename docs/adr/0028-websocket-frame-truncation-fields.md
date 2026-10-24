---
id: ADR-0028
title: WebSocket frames carry a truncation flag and their original size
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0028 — WebSocket frames carry a truncation flag and their original size

## Context

`AppMonitorWebSocketFactory` keeps at most `maxBodyBytes` of each frame's
payload (ADR-0025), but `WebSocketFrame` has no way to say so (DEBT-0025):
PLAN-0002 M1.10 shows frames in the desktop, which must not present a cut
frame as whole. ADR-0020 already solved the same problem for HTTP bodies;
ADR-0008 forbids renumbering fields.

Evidence, checked on 2026-10-22: `protocol/proto/appmonitor/v1/network.proto`
uses fields 1–4 of `WebSocketFrame`; the SDK holds every frame's payload in
memory before cutting it (`WebSocketCapture.frame`), so its size is always
known.

## Options

1. **`bool payload_truncated = 5` + `optional int64 payload_size = 6`** —
   the ADR-0020 shape, so the desktop renders frames and bodies with the same
   code; older peers decode "whole, size unknown".
2. **Only `optional int64 payload_size`**, truncation derived from
   `payload.size < payload_size` — one field less, but a different rule from
   bodies and no way to say "cut, size unknown" for a future SDK.

## Decision

Option 1. The OkHttp capture always sets `payload_size`; `payload_truncated`
is true when the payload is over `maxBodyBytes`.

## Consequences

- `websocket_frame.bin` was regenerated with `payload_size = 4`.
- The desktop shows "Truncated: N of M bytes shown" above an open frame and
  "N of M B" in its row (ADR-0029).
- The iOS SDK (M2) fills both fields the same way.
