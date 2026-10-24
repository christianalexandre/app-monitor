# 08 — Ktor capture

How `sdk/ktor` turns a KMP app's Ktor `HttpClient` calls into `network`
envelopes for the running monitor of SDD 04, on JVM, Android and iOS.
Code in `sdk/ktor/src/commonMain`; public API in ADR-0062, what is
captured and masked in ADR-0063, the hook and the masking code in ADR-0069,
WebSocket frames in ADR-0070.

## How it works

```
app ── HttpClient { install(AppMonitorKtor) }
         requestPipeline ─► sendPipeline: Before ─ State (HttpCookies) ─ Monitoring ─ Engine ─ Receive (SaveBody)
                                                                          │
                                                       AppMonitorKtor ────┴──► MonitorBridge.send ──► MonitorClient buffer (SDD 04)
```

- **Off until started.** The plugin reads `MonitorBridge.options` for each
  request sent: null (not started, stopped, `enabled = false`) → the
  request goes through untouched and nothing is built.
- **One exchange per request sent.** The interceptor sits at
  `HttpSendPipeline.Monitoring`, which runs once per hop: a redirect
  followed by `HttpRedirect` and a retry of `HttpRequestRetry` are each
  their own exchange. Headers set by the app, `DefaultRequest` or
  `HttpCookies` are seen (`AppMonitorKtorTest`); headers the engine adds (Darwin's
  `NSURLSession`, OkHttp) are not.
- **Marked on iOS.** Each hop captured on iOS is sent with
  `X-AppMonitor-Capture: ktor` (`Protocol.CAPTURE_HEADER`), set at
  `Monitoring` and shown in its `RequestStarted`; the Swift SDK skips a task
  carrying it, so a Darwin call is captured once (SDD 07, ADR-0064,
  ADR-0071). A hop not captured has it removed. JVM and Android never add it.
- **Masking** is the rule of SDD 05, member for member, pinned by
  `protocol/fixtures/masking.json` on the three targets: default and
  `maskedHeaders` → `██`, `maskedQueryParameters` in the query and a
  `name=value&…` fragment, URL user info, `Location` and
  `Content-Location` as URLs. It works on the URL as written (no
  normalisation); a relative `Location` is resolved against the request URL
  by RFC 3986 5.2 first, unless its authority has no host or a bad port.
  `masking = false` turns every rule off (ADR-0034). Bodies are never
  masked (DEBT-0019).
- **Body limit**: `maxBodyBytes`, as SDD 05.

| Event | Sent when | Body |
| --- | --- | --- |
| `RequestStarted` | Before the engine, once the headers are final | `setBody` of a string, bytes or a serialized object (`ByteArrayContent`), cut at `maxBodyBytes`. A stream (`ChannelWriterContent`, `ReadChannelContent`, multipart, upload progress): not read, empty with `body_truncated` and its length when known. No body: empty, no size. The content's `Content-Type` is added to the headers when absent. |
| `ResponseReceived` | When the engine returns and the receive pipeline has run | The body `SaveBody` kept for the app, read with `readRawBytes()` and cut. A streamed response (`prepareGet(…).execute { }`), or a saved body whose read throws: not read, empty with `body_truncated` and `Content-Length`. `101`: no body. |
| `RequestFailed` | The engine or the receive pipeline throws | `error` is the exception's `toString()`; the exception reaches the app unchanged. |

### One WebSocket

The upgrade is an exchange like any other: `RequestStarted` (masked, no
body) and `ResponseReceived` `101`, or `RequestFailed`. Its request id is
kept in the hop's request attributes; an interceptor at
`HttpResponsePipeline.Parse`, before `WebSockets` builds the app's session
at `Transform`, wraps the engine's session so each frame becomes a
`WebSocketFrame` with that id:

- **Received**: recorded when the reader takes it from the engine's
  `incoming`, before it is handed on, so an echo is never recorded before
  the frame it answers. **Sent**: recorded as it is written to the engine's
  `outgoing`.
- **Payload** cut at `maxBodyBytes`, `payload_truncated` and the original
  `payload_size` (ADR-0028); a close payload is the 2-byte code and the
  UTF-8 reason. Not masked (ADR-0063).
- **One sent close per session**: a second one (the `webSocket { }`
  builder closes on exit) never reaches the wire and is not recorded.
- **Per engine**: CIO and Darwin hand over a raw session, read and written
  by Ktor's `DefaultWebSocketSession`: every frame Ktor puts on the wire,
  pings, pongs and both closes included. OkHttp hands over its own default
  session: text and binary frames both ways and a close the app sends; the
  peer's close is recorded from `closeReason` when `incoming` ends, closed
  or failed with a cause, and the answer OkHttp sends to it is not seen.
- **Off until started**: no id is set while the monitor is off, so a
  session opened then is never wrapped; a frame after `stop()` is dropped.

A request id is a random UUID shared by an exchange's events; every
`RequestStarted` has `capture_layer = ADAPTER` (ADR-0038). Building an
event never throws into the app's call: a failure drops that event.

## Limits

- Streamed bodies, both ways, are not captured (ADR-0069 B).
- A response the `ContentEncoding` plugin decodes is captured encoded
  (ADR-0069).
- WebSocket frames read with `select { incoming.onReceive }` on an OkHttp
  session are not recorded; OkHttp keeps pings and pongs to itself
  (DEBT-0029). A payload compressed by `WebSocketDeflateExtension` (CIO) is
  recorded compressed (ADR-0070).
- On Darwin, Ktor may end a session by cancellation before the peer's close
  reaches it; the app's `incoming` then fails and no received close is
  recorded, plugin installed or not (ADR-0070).
- Two Kotlin implementations of the masking rule exist (DEBT-0055).
