# 07 — iOS URLSession capture

How `sdk/ios` turns every `URLSession` task of the app into `network`
envelopes for the running monitor of SDD 06. Code in
`sdk/ios/Sources/AppMonitor/URLSessionCapture.swift`, `WebSocketCapture.swift`,
`BackgroundCapture.swift`, `Mocks.swift`, `Replays.swift`, `Breakpoints.swift` and `Masking.swift`; the mechanism
is ADR-0043 (WebSocket tasks ADR-0045, background sessions ADR-0046, mocks ADR-0117, replay ADR-0124, breakpoints
ADR-0125), what is masked and
bounded ADR-0036. The grpc-swift adapter, a second product beside it, is
the last section (ADR-0099).

## How it works

```
app ── any URLSession ── task.resume() ──────────► RequestStarted ─┐
                         CFNetwork connection ───► ResponseReceived ├─► sink.send ──► MonitorClient buffer (SDD 06)
                           response, data, finish   or RequestFailed ┘   (MonitorHolder, or a test double)
```

- **Installed once, at the first `AppMonitor.start` with `enabled`.** The
  hooks stay for the life of the process. Each task reads `sink.options`
  when it first resumes: nil (not started, stopped) → the task is never
  captured, even if the monitor starts before it ends. Hence a session
  created before `start()` is captured, a task resumed before it is not;
  a background task is the exception (ADR-0131).
- **Hooks** (ADR-0043): `NSURLSessionTask.resume`; on
  `__NSCFURLLocalSessionConnection`, `_didReceiveResponse:sniff:`,
  `_didReceiveData:` and `_didFinishWithError:`, which find their task
  through the `_task` ivar; on `NSURLSession`, the six upload constructors
  (data and file, plain, completion handler, `async`). Each records, then
  calls the original with the same arguments. A missing `resume` or
  connection method turns the whole capture off with one log line.
- **What is captured**: `http` and `https` data, upload, download and
  WebSocket tasks of local sessions, and the tasks of background sessions.
  Stream tasks are skipped.
- **Masking**: Android's rule (SDD 05), member for member (ADR-0036 A):
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` plus
  `maskedHeaders` → `██`; `maskedQueryParameters` in the query (a field
  without `=` included) and in `name=value` fragment fields; a non-empty URL
  user name and password → `██`; `Location` and `Content-Location` resolved
  against the response URL and masked as URLs; a value with another scheme
  is masked as written by RFC 3986 generic syntax (ADR-0044), and so is a
  value Foundation's `URL` does not parse, relative ones included. Unlike OkHttp, the fields that are not
  masked keep the app's encoding. `masking = false` turns every rule off.
  `protocol/fixtures/masking.json` is read by both SDKs' tests.
- **Headers**: Foundation keeps them in dictionaries; they are sent sorted
  by name, case-insensitively.

### One task

| Event | Sent when | Body |
| --- | --- | --- |
| `RequestStarted` | The task's first `resume()`, before the original runs, on the caller's thread | `httpBody` or the upload data: the first `maxBodyBytes`, `body_size` the whole. An upload file: its first `maxBodyBytes` read on the caller's thread, `body_size` the file size. `httpBodyStream` or `uploadTask(withStreamedRequest:)`: empty, `body_truncated`, no size; the stream is never read. No body: empty, no size. `capture_layer = PLATFORM`. |
| `ResponseReceived` | `_didFinishWithError:` after an HTTP response, before the app's completion | Copied from each `_didReceiveData:` up to `maxBodyBytes`; `body_size` the bytes received. With an error after the response: cut, `body_truncated`, no size. A followed redirect sends only the final response. |
| `RequestFailed` | `_didFinishWithError:` without a response, a cancelled task included | `error` is `NSURLErrorDomain <code>: <description>`, never the error's `userInfo`. |

A task whose request carries `X-AppMonitor-Capture` (any value) is not
captured, WebSocket tasks included: `sdk/ktor` sets it on what it already
captured through Ktor's Darwin engine (SDD 08, ADR-0064, ADR-0071).

A request id is a random UUID kept on the task with its body and masking
(an associated object), so it lives as long as the task.

### One mocked task

The desktop's rules (SDD 12) answer a task without the network
(ADR-0110, ADR-0117):

- **Chosen at the first resume**, by the hook that sends `RequestStarted`:
  the session's first rule for the method and URL (`MockRuleSet.match`,
  `protocol/fixtures/mock_matching.json`), kept on the task's
  `TaskCapture` and sent as `RequestStarted.mock_rule_id`. Only a data or
  upload task of a local session; not a download, WebSocket, stream or
  background task, nor one `sdk/ktor` marked.
- **Answered by `MockProtocol`**, a `URLProtocol` the replaced
  `-[NSURLSessionConfiguration protocolClasses]` getter puts first, once,
  in every configuration without an `identifier`, so `URLSession.shared`
  and sessions made before `start` too. `canInit(with: task)` is true only
  for a task holding a rule. A response: status, headers (one name twice
  joined with `, `) and body. A failure: `NSURLErrorDomain`
  `NSURLErrorNetworkConnectionLost` with the rule's message as its
  description. Either after `delay_ms`, on a `Timer` of the loading
  thread; a task cancelled meanwhile fails as `NSURLErrorCancelled`.
- **Captured as any task**: the connection hooks see the protocol's
  response, body and error, so `ResponseReceived` and `RequestFailed`
  are the same as from the network, masking included.
- A missing getter logs one line and mocks nothing.

### One task sent again

The desktop's `ReplayRequest` (SDD 12) sends a captured task again
through the app's own session (ADR-0111 D.1, ADR-0124):

- **Kept at the first resume**, by the hook that sends `RequestStarted`:
  a sender per HTTP task, by request id, in the session's
  `MonitorClient` (`RecentRequests`, the last 200; emptied when the
  session ends). It holds the task's `originalRequest` and upload body,
  and its session weakly.
- **Sent** as a new task on that session with a completion handler that
  drops the result, so the app's delegate gets no data or completion for
  it: a data task (a download task included), or an upload task from the
  same data or file. The task is captured as any other, mocked if a rule
  matches, with `RequestStarted.replay_of` the original's request id.
- **Refused** with `ReplayRefused` and a reason: a request not kept (an
  older one, a past session's, a WebSocket), a background session's task,
  a streamed body, a session the app released, or one it invalidated,
  read from `NSURLSession`'s `_invalid_ivar` before the task is made: a
  task made on an invalidated session raises an exception that ends the
  app (DEBT-0084 for the race).

### One held task

The desktop's breakpoints (SDD 12) hold a task's request before it is
sent and its response before the app reads it (ADR-0111, ADR-0125):

- **Chosen at the first resume**, by the hook that sends `RequestStarted`:
  a data or upload task of a local session, as for a mock, without a
  streamed body, that the session's first breakpoint holding the request
  or the response matches (`Breakpoints.match`, the mock matcher). The
  `TaskCapture` keeps the request rule and the request as resumed: by
  `startLoading` its `httpBody` is a stream.
- **Loaded by `HoldProtocol`**, put before `MockProtocol` by the same
  getter. A request rule: the hook sends no `RequestStarted`; the protocol
  sends `BreakpointHit` with the request (masked, no mock id) and waits on
  the loading thread's run loop. A resume applies only its fields (method,
  URL, `set_headers` and `removed_headers` by name, body); the mock is
  matched on the request as resumed; then one `RequestStarted` with what
  goes out. An abort sends the held `RequestStarted` and fails the task
  as a mock failure; a URL that is not `http` or `https` with a host, or
  an empty method, fails it with `NSURLErrorBadURL` "Breakpoint edit
  refused: …".
- **Sent on** through a new session from a copy of the task's session
  configuration (cookies, cache, headers, timeouts), its task marked so
  it is never captured, held or mocked; or answered by the mock rule.
  Its challenges and redirects go to the app's delegates on the app's
  delegate queue, in URLSession's order: the task's delegate, then the
  session's (session-level method first for a session-wide challenge);
  none, default handling and the redirect followed. A refused redirect
  is the task's response (ADR-0130). Without a response rule matching the request as sent, the response is
  handed to the task as it comes.
- **A response rule**: the response is read whole, or held once its body
  passes `maxBodyBytes` (`body_truncated`, `body_size` its
  `Content-Length`, else none). Past that point the task keeps reading,
  so the server never sees an idle connection, and the rest goes to a
  temporary file, not memory. The response is sent in `BreakpointHit`
  with the request as sent, then handed over unchanged or with the
  resume's status, headers and body; past the limit, the file follows in
  1 MiB chunks, then whatever is still coming, unless the resume replaces
  the body. A write or read of that file that fails, or reads back fewer
  bytes than written, fails the task, so the app never gets a body with a
  gap. The file is deleted at the resume, the abort or a cancel.
  An abort fails the task.
- **Ends** at the resume, the abort, the hit's timeout or the session's
  end, the last two unchanged. A task the app cancels while held drops
  the hold and sends the held `RequestStarted` before the capture's
  `RequestFailed`.
- **Captured as any task**: the connection hooks see what the protocol
  hands over, so `ResponseReceived` is what the app read.

### One WebSocket task

Hooks on `__NSURLSessionWebSocketTask`'s own methods (ADR-0045); the capture
is kept on the task under its own key, so the connection hooks above never
see it. Foundation turns `ws` into `http` in the request of a task made
from a URL, not from a `URLRequest`; the capture does the same for the
latter, so the captured URL is `http` or `https`, as on Android (ADR-0071).

| Event | Sent when |
| --- | --- |
| `RequestStarted` | The first `resume()`, as for a data task, no body. |
| `ResponseReceived` | `_onqueue_didReceiveResponse:completion:`: the `101` (or the status that refused the upgrade) and its masked headers, no body. |
| `RequestFailed` | `connection:didFinishLoadingWithError:` with an error before any response, e.g. a refused connection. |
| `WebSocketFrame` sent | `sendMessage:completionHandler:`, before the original runs, so an echo is never recorded before it; text or binary. `cancelWithCloseCode:reason:` on a running task: a close frame. |
| `WebSocketFrame` received | The app's `receive` handler, before the app gets the message; an error there after the peer's close frame, read from `closeCode` and `closeReason`: a received close frame. |

Payloads are cut at `maxBodyBytes` with `payload_truncated` and
`payload_size` (ADR-0028), never masked (ADR-0036); a close payload is the
2-byte code and the reason. Only the first close of either side is
recorded.

### One background task

A background session's transfers run in `nsurlsessiond`; the in-process
`__NSURLBackgroundSession` gets each result by task identifier (ADR-0046).
The `resume` hook makes the `TaskCapture` as for a local task and, when
`task.session` is a background session, records it in a `BackgroundTasks`
table kept on that session. The app's delegate is never replaced.

| Event | Sent when | Body |
| --- | --- | --- |
| `RequestStarted` | The first `resume()`, as for a local task | A file upload's first `maxBodyBytes`, as above. |
| `ResponseReceived` | `backgroundTask:didCompleteWithError:taskMetrics:info:reply:` with the task's `response`, before the app's `didCompleteWithError` | Download: the file of `backgroundDownloadTask:didFinishDownloadingToURL:response:reply:`, read up to `maxBodyBytes` before the app's `didFinishDownloadingTo` can move it, `body_size` the file size. Data and upload: each `backgroundDataTask:didReceiveData:withReply:` chunk, as `_didReceiveData:`. |
| `RequestFailed` | The same completion without a response | As for a local task. |

A task the table lacks at one of the three callbacks, resumed before
`start()` or in an earlier launch, is adopted while the monitor runs
(ADR-0131): a `TaskCapture` is made there, and at completion
`RequestStarted` is sent from the first transaction's request of the
task's metrics (the `__CFN_TaskMetrics` argument wrapped in a
`URLSessionTaskMetrics` through its `__metrics` ivar), stamped
`taskInterval.start`, then the response. Not `http(s)` or carrying
`sdk/ktor`'s capture header → nothing is sent. Its request body is not
seen: truncated unless `GET` or `HEAD`.

Missing one of the three session methods leaves background tasks with only
`RequestStarted` and logs one line. Background sessions cannot run in a bare
`xctest` process, so these cases are tested hosted in an app on the
simulator (`make swift-sim`, ADR-0047).

## The grpc-swift adapter

`AppMonitorGRPC`, a second product of the same package (both manifests,
ADR-0054), holds `AppMonitorGRPCInterceptor`, a gRPC Swift 2
`ClientInterceptor` (`grpc-swift-2` from 2.3.0, Apache-2.0, iOS 18 and
macOS 15; ADR-0099 A.1). The app passes it once in the client's
`interceptors`; an app without the product resolves `grpc-swift-2` but
never builds or links it (`make publish-dry-run` checks the binary). Code in
`sdk/ios/Sources/AppMonitorGRPC/`, events built by `AdapterExchange`
(`package` access, `AppMonitor/AdapterCapture.swift`) so the protobuf types
stay `internal`. Release builds exclude it with `AppMonitor*` (ADR-0155);
its public API is `api/AppMonitorGRPC.swiftinterface`.

One RPC is one exchange with `capture_layer = ADAPTER` (ADR-0099 C.1, ADR-0102):

| Part of the call | Event |
| --- | --- |
| Start | `RequestStarted`: method `POST`, URL `grpc://<authority>/<package.Service>/<Method>`, request metadata as headers (`-bin` values in base64), masked like `URLSession`'s |
| Authority | the client's `remotePeer`: `ipv4:` and `ipv6:` keep address and port; another peer its kind (`in-process`, `unix`) |
| Request message, unary or server streaming | `RequestStarted.body`; the producer runs once before the call and the same messages are replayed to the transport |
| Request messages, client or bidirectional streaming | one sent binary `WebSocketFrame` each, before the transport gets it |
| Response message, unary or client streaming | `ResponseReceived.body` |
| Response messages, server or bidirectional streaming | one received binary `WebSocketFrame` each, before the app gets it |
| End with a status (OK, or an error after the server accepted the call) | `ResponseReceived`: status 200, headers = initial metadata, trailers, then `grpc-status` and `grpc-message` (when not empty) |
| Call the server never accepted, or an error that is not a status | `RequestFailed`: `grpc-status N (name): message`, or the error's description |
| Call dropped before its status (response stream left unread, cancelled) | `RequestFailed`: `call ended before its status` |

- **Which side streams** comes from `MethodDescriptor.type` (set by
  `protoc-gen-grpc-swift-2`); a descriptor without one counts as
  bidirectional, so every message is a frame.
- **A message** is serialized again with SwiftProtobuf when it is a
  `SwiftProtobuf.Message`; any other type is sent empty with
  `body_truncated` and no size. Bodies and frames are cut at
  `maxBodyBytes` like every body (ADR-0020).
- **gRPC Swift 1** apps and grpc-swift calls without the interceptor are
  left to layers 3 and 4 (PLAN-0009).
- **Tests**: `GRPCCaptureTests` runs a unary, a server-streaming, a
  rejected and a failing call against an in-process `GRPCServer`
  (`GRPCInProcessTransport`), and an idle monitor that sends nothing.

## The connection layer

Every IPv4 and IPv6 stream socket the app's own code connects, a
third-party SDK's included, is one exchange with `capture_layer =
CONNECTION` and no content (ADR-0100, ADR-0103). C in
`sdk/ios/Sources/AppMonitorConnections/` (fishhook vendored at
`aadc161`, BSD-3-Clause, its two functions renamed so an app's own copy
links), reporting in `AppMonitor/ConnectionCapture.swift`; nothing installs the
hooks until `AppMonitor.start`, and release builds exclude it (ADR-0155).

- **Installed once**, at the first `start()` with `enabled` and
  `captureConnections` (default true). Each `connect` reads
  `sink.options`: idle, stopped or `captureConnections` false → the
  socket is not reported.
- **Which images**: every image loaded now or later except those in the
  shared cache and arm64e ones (signed pointers). So the app binary and
  its embedded frameworks are reached, gRPC-Core and its BoringSSL
  included; calls made inside system libraries, `URLSession`'s and
  Network.framework's sockets (the SDK's own desktop connection among
  them), are not, so layer 1's traffic is never reported twice.

| Rebound symbol | What it reports |
| --- | --- |
| `getaddrinfo` | Nothing sent: each address it returns is mapped to the name asked (at most 512 kept) |
| `connect` | On an `AF_INET` or `AF_INET6` stream socket not counted yet: `RequestStarted` dated before the call, method `CONNECT`, URL `tcp://<name or address>:<port>`. An error other than `EINPROGRESS` or `EINTR`: `RequestFailed` at once |
| `read`, `readv`, `recv`, `recvfrom`, `recvmsg` | Bytes read, added to the socket's count (one table lookup when the socket is not counted) |
| `write`, `writev`, `send`, `sendto`, `sendmsg` | Bytes written, likewise |
| `close` | Before the original: `ResponseReceived` with status 0, no body and not `body_truncated`, `body_size` read, `sent_size` written; a pending `SO_ERROR`, or a socket that never connected and moved no byte, is `RequestFailed` instead |

- **Not reported**: loopback addresses (`127.0.0.0/8`, `::1`, mapped
  IPv4), where the desktop is on the simulator; a desktop the SDK found,
  every mDNS answer and the manual host, by address or the name it was
  resolved from and its port, where another SDK of the app, `sdk/core`
  in a KMP app, connects too (ADR-0100 C.1); one opened before that
  answer is reported (DEBT-0090); sockets numbered 4096 and up;
  Network.framework's `nw_connection_*` (DEBT-0076).
- **Errors** read `NSPOSIXErrorDomain <errno>: <strerror>`, e.g.
  `NSPOSIXErrorDomain 61: Connection refused`.
- **Cost**: a rebound `write` on a socket that is not counted measured
  404 ns against 403 ns for the original on the macOS host, 388 against
  387 on the iPhone 17 Pro simulator (`reboundWriteCost`, best of five
  rounds of 200 000 calls to `/dev/null`, Apple Silicon).
- **Tests**: `ConnectionCaptureTests` drives raw BSD sockets from the test
  image against `TestSocketServer` on loopback (loopback reported for the
  test): a blocking client by name with its bytes and duration, a
  non-blocking connect counted once, a refused port, loopback, the option
  off and an idle monitor, a `MonitorClient` to a `TestDesktop` that
  reports nothing, and sockets to desktops found by a `FakeBrowser` and a
  manual host on TEST-NET-1 addresses, skipped while another port on the
  same address is reported. It runs in `swift test` and, hosted in `TestHost` on
  the simulator, in `make swift-sim`. It nests with `URLSessionCaptureTests`
  and `GRPCCaptureTests` in the serialized `ProcessWideHooks`: a mock rule
  another suite set answers its `URLSession` call otherwise.

## Limits

- A mocked `3xx` reaches the app as is; the protocol never redirects.
- A replay of a redirected task sends its first request again; a
  WebSocket is refused as not kept.
- The hold is tested on the macOS host only (DEBT-0086), a session-wide
  challenge to a held task not at all (ADR-0130).
- `MockProtocol` and `HoldProtocol` read the task they were made for from
  their own property, set in `init(task:cachedResponse:client:)`, never
  `URLProtocol.task`: on iOS 16 that getter raises "unrecognized
  selector" and ends the app. Nothing below iOS 26 is tested (DEBT-0111).

- Headers CFNetwork adds at send time, `Cookie` from the cookie storage
  among them, are not captured (DEBT-0037).
- An iOS WebSocket that drops after the upgrade sends no event; a peer's
  close without a pending `receive` and a bare `cancel()` record no close
  frame (DEBT-0038). Ping and pong frames are not seen (DEBT-0029).
- An adopted background task's request body is not captured, and no test
  relaunches the app to adopt an earlier launch's task (ADR-0131).
- The hooks are private CFNetwork names, proven by `swift test` on the
  macOS host and, for background sessions, on the iOS simulator; the rest
  on iOS by M2.12 and M2.13.
- An event whose URL and headers exceed 256 KiB is dropped whole by
  `MonitorClient` (DEBT-0023).
- The desktop shows a gRPC message as bytes; it has no `.proto` to decode
  fields (ADR-0099 C.1). A status other than OK after the server accepted
  the call is status 200 with `grpc-status`, which the desktop reads as an
  error (ADR-0107, SDD 09).
- A connection the app keeps open shows as pending until it closes; one
  reset after bytes moved is `RequestFailed` without its counts
  (ADR-0103). A non-blocking connect whose error the app already read
  with `SO_ERROR` fails as `57: Socket is not connected`.
