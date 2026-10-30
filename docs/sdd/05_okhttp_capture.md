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
- **A call `sdk/ktor` captured is skipped.** A request carrying
  `X-AppMonitor-Capture` (any value), set by the Ktor plugin on its OkHttp
  engine, goes on with the header removed and nothing captured, whether
  the monitor runs or not (SDD 08, ADR-0129). Tests:
  `AppMonitorInterceptorTest`, and `OkHttpEngineDedupeTest` in `sdk/ktor`.
- **Masking**, on the device before the envelope is built:
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` plus
  `maskedHeaders` keep their name and get the value `██`;
  `maskedQueryParameters` do the same in the URL's query and in a
  `name=value&…` fragment (ADR-0033), and a non-empty URL user name and
  password are always `██` (ADR-0032). `██` is sent as is, not
  percent-encoded. A response's `Location` and `Content-Location` are
  resolved against the request URL and get the same URL rule; a value with
  another scheme (`com.example.app:/oauth?code=…`) gets it by RFC 3986
  generic syntax, only the masked spans changed (ADR-0044), and so is a
  value `HttpUrl` does not resolve, with or without a scheme. Names compare
  case-insensitively, as in OkHttp's `HttpLoggingInterceptor`. Bodies are
  never masked (DEBT-0019).
  `masking = false` skips every rule above and sends each value as OkHttp
  saw it; `maskedHeaders` and `maskedQueryParameters` are then ignored
  (ADR-0034). A new masking rule honours the switch, with a test, and a
  case in `protocol/fixtures/masking.json`, which the iOS SDK reads too
  (SDD 07).
- **Body limit**: `maxBodyBytes`, 1 MiB by default and at most
  `Protocol.MAX_BODY_BYTES` (`AppMonitorOptions` rejects more, ADR-0025).

### Added by the Gradle plugin

`io.github.christianalexandre.appmonitor` (`gradle-plugin/`, ADR-0112,
ADR-0126, ADR-0127), applied in an Android app module, adds the SDK, its
no-ops in release (ADR-0157, SDD 01), and the interceptor without a line of
code:

- In each variant with `debuggable = true` whose dependency graph has
  `appmonitor-okhttp`, OkHttp's constructor `OkHttpClient(builder)`
  calls `AppMonitorOkHttp.install(builder)` first (AGP instrumentation,
  scope `ALL`: library clients included). `OkHttpClient()` and
  `Builder.build()` both go through it. `install` adds an
  `AppMonitorInterceptor` unless the builder has one, application or
  network: a hand-written line or a `newBuilder()` client gets none more.
- Release variants, a variant without the SDK (one build line, "OkHttp is
  not rewritten", when AGP runs the transform) and
  `appMonitor { injectOkHttp = false }` leave OkHttp's bytes untouched.
- `OkHttpClient.newWebSocket` calls `AppMonitorOkHttp.newWebSocket(this,
  request, listener)` first and returns its socket when not null (ADR-0159).
  The entry returns null while the monitor is idle, for a request tagged
  with a `WebSocketCapture` (the factory's own call into the client) and for
  one carrying `X-AppMonitor-Capture` (the Ktor plugin's). Otherwise it opens
  the socket through `AppMonitorWebSocketFactory(client)`. AGP recomputes
  that method's frames. `AppMonitorWebSocketFactory` by hand is for apps
  that set `injectOkHttp = false`.
- Tests: `ClientVisitorTest` (OkHttp's real `OkHttpClient` rewritten
  and loaded, a client from each path, `newWebSocket` asking the entry
  first), `AppMonitorOkHttpWebSocketTest` in the SDK, `OkHttpInjectionTest` (TestKit: consumers with stand-in `:sdk:*`
  projects, their APKs read with `dexdump`: debug real, release no-op, no
  OkHttp brought to an app without it, a release resolving a real artifact
  failing), `AppMonitorOkHttpTest` in the SDK. The Android sample has
  no interceptor and no WebSocket line; `make e2e-android` captures both.

### One HTTP call

| Event | Sent when | Body |
| --- | --- | --- |
| `RequestStarted` | Before `chain.proceed`, after a request breakpoint | Written into a sink that keeps `maxBodyBytes` and stops the write past them. One-shot or duplex: not read, `body_truncated` with an empty body. No body: empty, no size. A `Content-Type` from the body is added to the headers when the app set none. |
| `ResponseReceived` | When the app reaches the end of the body or closes it; at once for `Content-Length: 0` and `101` | Copied as the app reads it, never read ahead: the app's call is not delayed. `body_size` is the bytes read when the end was reached, else `Content-Length` when known. |
| `RequestFailed` | `chain.proceed` throws | `error` is the exception's `toString()`; the exception is rethrown unchanged. |

A request id is a random UUID shared by the call's events. Every
`RequestStarted`, WebSocket upgrades included, has `capture_layer = ADAPTER`
(ADR-0038). Building an event
never throws into the app's call: a failure drops that event.

### A mocked call

When `MonitorBridge.mockFor` returns a rule (SDD 04, ADR-0110), the
interceptor answers without `chain.proceed` (ADR-0115):

- `RequestStarted` as above, with `mock_rule_id`.
- After `delay_ms` (`Thread.sleep` in 50 ms steps that check
  `call.isCanceled()`), a rule's response becomes a
  `Response` with its status, headers and body, protocol HTTP/1.1, and
  `ResponseReceived` is sent at once with the whole body. A rule's
  failure, a call cancelled during the delay or an interrupted delay
  throws an `IOException` (the rule's message) after `RequestFailed`.
- WebSocket upgrades are never mocked: the interceptor skips them first.
- Added with `addNetworkInterceptor` (`chain.connection()` non-null), no
  rule is looked up: OkHttp requires a network interceptor to call
  `proceed` once (ADR-0128).
- Tests: `MockAnswerTest` against `mockwebserver3` (no request reaches
  it, delay kept, failure, unmatched call untouched, a matching call
  through a network interceptor sent).

### A held call

When `MonitorBridge.breakpointFor` returns a rule (SDD 04, ADR-0118,
ADR-0123), the interceptor holds the call on its own thread
(`runBlocking`), checking `Call.isCanceled()` every 50 ms:

| Stage | Held | The hit carries | Then |
| --- | --- | --- | --- |
| Request | Before the mock lookup and `chain.proceed` | The request as it would go out, masked, as a `RequestStarted` | The resume's method, URL, headers and body applied to the real request: a `██` header left alone goes out with its value. `RequestStarted` is sent once, for the request as resumed; the mock lookup runs on it |
| Response | After `chain.proceed` or a mock's answer, before the app reads the body (not for `101`) | The request as sent and the response, its body peeked up to `maxBodyBytes` (`BufferedSource.peek`, not consumed) | The resume's status and headers applied; a replaced body is the app's whole body and `ResponseReceived` is sent at once with it; otherwise the body is captured as the app reads it |

- An abort, a cancel or an interrupt during the wait, or an edit OkHttp
  refuses ("Breakpoint edit refused: …": a URL that does not parse, a
  method that refuses the body, another host or port under
  `addNetworkInterceptor`, ADR-0128) sends `RequestStarted` when not yet
  sent, then `RequestFailed`, and throws an `IOException`
  (`InterruptedIOException` for an interrupt).
- No answer within the hit's timeout, or the session's end: the call
  goes on unchanged.
- WebSocket upgrades are never held.
- Tests: `BreakpointTest` against `mockwebserver3` (a request edited
  with the masked header kept, aborted, a refused edit, a response
  edited, an unchanged one read whole past its held part, a mocked
  response held, an empty response held, a cancel while held, a network interceptor's resume to
  another host refused and one on the same host sent).

### A call sent again

Every captured call, mocked or not, is kept for a replay (SDD 04,
ADR-0122 B.1):

- On the desktop's request the interceptor clones `chain.call()` and
  enqueues the clone on the app's client: every application and network
  interceptor runs again from the call's original request.
- The clone is marked in the interceptor's own map until it ends, so its
  `RequestStarted` (each hop's, under `addNetworkInterceptor`) carries
  `replay_of`; its response is read to the end and closed, so it is
  captured whole.
- A one-shot or duplex request body is refused with a reason.
- Tests: `ReplayTest` against `mockwebserver3` (sent again with the
  header an earlier interceptor adds and the same body, marked, kept in
  turn; one-shot body refused).

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
