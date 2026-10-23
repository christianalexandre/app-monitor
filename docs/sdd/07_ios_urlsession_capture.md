# 07 — iOS URLSession capture

How `sdk/ios` turns every `URLSession` task of the app into `network`
envelopes for the running monitor of SDD 06. Code in
`sdk/ios/Sources/AppMonitor/URLSessionCapture.swift`, `WebSocketCapture.swift`
and `Masking.swift`; the mechanism is ADR-0043 (WebSocket tasks ADR-0045),
what is masked and bounded ADR-0036.

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
  created before `start()` is captured, a task resumed before it is not.
- **Hooks** (ADR-0043): `NSURLSessionTask.resume`; on
  `__NSCFURLLocalSessionConnection`, `_didReceiveResponse:sniff:`,
  `_didReceiveData:` and `_didFinishWithError:`, which find their task
  through the `_task` ivar; on `NSURLSession`, the six upload constructors
  (data and file, plain, completion handler, `async`). Each records, then
  calls the original with the same arguments. A missing `resume` or
  connection method turns the whole capture off with one log line.
- **What is captured**: `http` and `https` data, upload, download and
  WebSocket tasks of local sessions. Stream tasks are skipped.
- **Masking**: Android's rule (SDD 05), member for member (ADR-0036 A):
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` plus
  `maskedHeaders` → `██`; `maskedQueryParameters` in the query (a field
  without `=` included) and in `name=value` fragment fields; a non-empty URL
  user name and password → `██`; `Location` and `Content-Location` resolved
  against the response URL and masked as URLs; a value with another scheme
  is masked as written (ADR-0044). Unlike OkHttp, the fields that are not
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

A request id is a random UUID kept on the task with its body and masking
(an associated object), so it lives as long as the task.

### One WebSocket task

Hooks on `__NSURLSessionWebSocketTask`'s own methods (ADR-0045); the capture
is kept on the task under its own key, so the connection hooks above never
see it. Foundation has already turned `ws` into `http` in the task's
request, so the captured URL is `http` or `https`, as on Android.

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

## Limits

- Headers CFNetwork adds at send time, `Cookie` from the cookie storage
  among them, are not captured (DEBT-0037).
- An iOS WebSocket that drops after the upgrade sends no event; a peer's
  close without a pending `receive` and a bare `cancel()` record no close
  frame (DEBT-0038). Ping and pong frames are not seen (DEBT-0029).
- Background sessions run out of process and are not captured (M2.9).
- The hooks are private CFNetwork names, proven by `swift test` on the
  macOS host; on iOS by M2.12 and M2.13.
- An event whose URL and headers exceed 256 KiB is dropped whole by
  `MonitorClient` (DEBT-0023).
