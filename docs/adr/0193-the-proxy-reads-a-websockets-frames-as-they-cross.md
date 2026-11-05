---
id: ADR-0193
title: The proxy reads a WebSocket's frames as they cross, inflating permessage-deflate
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-04
superseded_by:
---

# ADR-0193 — The proxy reads a WebSocket's frames as they cross, inflating permessage-deflate

## Context

After a `101` the proxy took both HTTP codecs off and relayed raw bytes
(SDD 11): a WebSocket through it was stored as one exchange with status
101 and no frames (DEBT-0077), where the SDKs send each frame as a
`WebSocketFrame` after the `ResponseReceived` (ADR-0028, ADR-0045,
`AppMonitorWebSocketFactory`).

Forces:

- The relay already paces each side by the other's writability
  (ADR-0191); whatever reads the frames must not change what is relayed
  or when.
- RFC 6455 §5.2: a 2 to 14 byte header (FIN, RSV1, opcode, mask bit,
  7, 16 or 64 bit length, a 4 byte mask key on every client frame);
  data messages may be fragmented with control frames between the
  fragments; control frames are at most 125 bytes and not fragmented.
- RFC 7692: with `permessage-deflate` agreed in the `101`'s
  `Sec-WebSocket-Extensions`, RSV1 on a message's first frame means its
  payload is raw DEFLATE with the trailing `00 00 ff ff` removed; without
  `no_context_takeover` the window carries over from message to message.
  OkHttp offers it on every WebSocket and compresses messages from
  1024 bytes (`ProxyWebSocketTest`: the test fails with inflation off).
- An SDK records messages as the app sees them, uncompressed and whole.

## Options

1. **A passive reader per direction beside the raw relay** — each
   `ByteBuf` is parsed by index, without consuming it, before it is
   written on; the parser keeps its own state across buffers, unmasks
   client payloads, joins fragments into one message, and inflates with
   one `java.util.zip.Inflater` per direction kept for the connection,
   which serves both context takeover and `no_context_takeover`. The
   bytes relayed are the client's and the origin's own.
2. **Netty's WebSocket codecs on each side** — `WebSocket08FrameDecoder`
   and `Encoder` plus the deflate handlers: the proxy would re-encode
   every frame, so the origin no longer receives the client's bytes (new
   masks, possibly new fragmentation and compression), and both sides'
   extension parameters must be mirrored.
3. **Keep the raw relay** — DEBT-0077 stays open; an app's WebSockets
   already reach the desktop through the SDKs.

## Decision

Option 1: `WebSocketCapture`, made for a `101` whose `Upgrade` is
`websocket`, reads what `ClientHandler.relay` writes and sends each
message as one `WebSocketFrame` with its opcode, and each ping, pong and
close frame, through the exchange's `Capture`, which takes frames after
its response once kept open. A closed connection with no close frame
either way ends the exchange as failed. A frame the reader cannot parse
(a reserved opcode, a fragmented or oversized control frame, a
compressed message without the extension, an inflate error) stops the
capture of that direction; the relay goes on.

## Consequences

- A WebSocket through the proxy shows its frames in the window and in
  MCP like an SDK's, payloads uncompressed and bounded as bodies are
  (`Protocol.MAX_BODY_BYTES`, its full size kept).
- Each frame header is read byte by byte and each payload copied once;
  an inflated message keeps at most `MAX_BODY_BYTES` while the inflater
  still reads all of it, to keep the window.
- WebSockets over HTTP/2 (RFC 8441, extended `CONNECT`) are relayed as
  any stream, their frames not read; no client of the proxy uses them
  today (SDD 11, *Limits*).
- Pays DEBT-0077.
