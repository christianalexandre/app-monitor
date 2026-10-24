# 08 — Ktor capture

How `sdk/ktor` turns a KMP app's Ktor `HttpClient` calls into `network`
envelopes for the running monitor of SDD 04, on JVM, Android and iOS.
Code in `sdk/ktor/src/commonMain`; public API in ADR-0062, what is
captured and masked in ADR-0063, the hook and the masking code in ADR-0069.

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
| `ResponseReceived` | When the engine returns and the receive pipeline has run | The body `SaveBody` kept for the app, read with `readRawBytes()` and cut. A streamed response (`prepareGet(…).execute { }`): not read, empty with `body_truncated` and `Content-Length`. `101`: no body. |
| `RequestFailed` | The engine or the receive pipeline throws | `error` is the exception's `toString()`; the exception reaches the app unchanged. |

A request id is a random UUID shared by an exchange's events; every
`RequestStarted` has `capture_layer = ADAPTER` (ADR-0038). Building an
event never throws into the app's call: a failure drops that event.

## Limits

- Streamed bodies, both ways, are not captured (ADR-0069 B).
- A response the `ContentEncoding` plugin decodes is captured encoded
  (ADR-0069).
- WebSocket frames are not captured yet (PLAN-0005 M4.6).
- Two Kotlin implementations of the masking rule exist (DEBT-0055).
