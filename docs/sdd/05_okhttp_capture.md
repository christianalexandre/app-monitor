# 05 — OkHttp capture

How `sdk/android-okhttp` turns an app's OkHttp calls and WebSockets into
`network` envelopes for the running monitor of SDD 04. Code in
`sdk/android-okhttp/src/commonMain` (JVM + Android, ADR-0025); public types
in ADR-0017, what is masked and bounded in ADR-0018.

## How it works

```
app ── OkHttpClient ── AppMonitorInterceptor ──► MonitorBridge.send ──► MonitorClient buffer (SDD 04)
app ── AppMonitorWebSocketFactory(client) ──┘        (sdk/core, @InternalAppMonitorApi)
```

- **Off until started.** Both types read `MonitorBridge.options` per call or
  WebSocket: null (not started, stopped, `enabled = false`) → the call goes
  through untouched and nothing is built.
- **Masking**, on the device before the envelope is built:
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` plus
  `maskedHeaders` keep their name and get the value `██`;
  `maskedQueryParameters` do the same in the URL's query and in a
  `name=value&…` fragment (ADR-0033), and a non-empty URL user name and
  password are always `██` (ADR-0032). `██` is sent as is, not
  percent-encoded. A response's `Location` and `Content-Location` are
  resolved against the request URL and get the same URL rule; a value with
  another scheme (`com.example.app:/oauth?code=…`) gets it by RFC 3986
  generic syntax, only the masked spans changed (ADR-0044). Names compare
  case-insensitively, as in OkHttp's `HttpLoggingInterceptor`. Bodies are
  never masked (DEBT-0019).
  `masking = false` skips every rule above and sends each value as OkHttp
  saw it; `maskedHeaders` and `maskedQueryParameters` are then ignored
  (ADR-0034). A new masking rule honours the switch, with a test, and a
  case in `protocol/fixtures/masking.json`, which the iOS SDK reads too
  (SDD 07).
- **Body limit**: `maxBodyBytes`, 1 MiB by default and at most
  `Protocol.MAX_BODY_BYTES` (`AppMonitorOptions` rejects more, ADR-0025).

### One HTTP call

| Event | Sent when | Body |
| --- | --- | --- |
| `RequestStarted` | Before `chain.proceed` | Written into a sink that keeps `maxBodyBytes` and stops the write past them. One-shot or duplex: not read, `body_truncated` with an empty body. No body: empty, no size. A `Content-Type` from the body is added to the headers when the app set none. |
| `ResponseReceived` | When the app reaches the end of the body or closes it; at once for `Content-Length: 0` and `101` | Copied as the app reads it, never read ahead: the app's call is not delayed. `body_size` is the bytes read when the end was reached, else `Content-Length` when known. |
| `RequestFailed` | `chain.proceed` throws | `error` is the exception's `toString()`; the exception is rethrown unchanged. |

A request id is a random UUID shared by the call's events. Every
`RequestStarted`, WebSocket upgrades included, has `capture_layer = ADAPTER`
(ADR-0038). Building an event
never throws into the app's call: a failure drops that event.

### One WebSocket

`AppMonitorWebSocketFactory` wraps any `WebSocket.Factory` (the
`OkHttpClient`):

- `RequestStarted` (masked, no body) at `newWebSocket`; `ResponseReceived`
  `101` at `onOpen`; `RequestFailed` at `onFailure`. The request carries a
  tag so `AppMonitorInterceptor` on the same client skips the upgrade.
- `WebSocketFrame` for each text, binary and close frame, sent or received,
  payload cut at `maxBodyBytes`, with `payload_truncated` and the original
  `payload_size` (ADR-0028). A close payload is the 2-byte code and the
  UTF-8 reason (RFC 6455).
- The app's listener receives the wrapping `WebSocket`, so replies sent from
  callbacks are captured. A sent frame is recorded under the same lock as
  received ones, so an echo is never recorded before the frame it answers.

## Limits

- A response body the app neither reads to its end nor closes sends no
  `ResponseReceived`.
- A failure while reading the body ends as a truncated `ResponseReceived`,
  without the error.
- Ping and pong frames are handled inside OkHttp and not seen (DEBT-0029).
- An event whose URL and headers exceed 256 KiB is dropped whole by
  `MonitorClient` (DEBT-0023).
