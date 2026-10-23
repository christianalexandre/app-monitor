---
id: ADR-0045
title: URLSession WebSocket tasks are captured by hooks on the task's own methods
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0045 — URLSession WebSocket tasks are captured by hooks on the task's own methods

## Context

PLAN-0003 M2.8 captures the frames of `URLSessionWebSocketTask`, bounded as
`WebSocketFrame` (ADR-0028). Already decided, not reopened here: the hooks
mechanism and its `resume` entry point (ADR-0043), frames are not masked and
are cut at `maxBodyBytes` (ADR-0036), the upgrade is a `RequestStarted`,
`ResponseReceived` or `RequestFailed` and a close payload is the 2-byte code
and the reason, as in OkHttp's capture (SDD 05).

Evidence, checked on 2026-10-23 on macOS 26.4 (the `swift test` host) with
throwaway probes of the Objective-C runtime against a Network.framework echo
server:

- A WebSocket task is `__NSURLSessionWebSocketTask` (superclass
  `__NSCFLocalSessionTask`); it overrides `isKindOfClass:`, so
  `is URLSessionWebSocketTask` holds. It defines
  `sendMessage:completionHandler:`, `receiveMessageWithCompletionHandler:`,
  `cancelWithCloseCode:reason:`, `_onqueue_didReceiveResponse:completion:`
  and `connection:didFinishLoadingWithError:`.
- Swift's `async` `send(_:)` and `receive()` go through the two message
  methods; the received message object answers `type`, `data` and `string`.
- `_onqueue_didReceiveResponse:completion:` receives the `101` response with
  its headers. On the same task, CFNetwork's connection calls
  `_didFinishWithError:` with nil **before** that response, and
  `_didReceiveData:` with empty data: ADR-0043's HTTP hooks would end it as
  a failure.
- A refused connection calls `connection:didFinishLoadingWithError:` with the
  connection and a `kCFErrorDomainCFNetwork -1004` error; a working socket
  closed by either side does not call it.
- `cancel(with:reason:)` calls `cancelWithCloseCode:reason:` and never
  `_sendCloseCode:reason:`. A close from the peer is in the task's public
  `closeCode` and `closeReason` when the pending receive fails (POSIX 57).
- `webSocketTask(with:)` turns a `ws` URL into `http` in `originalRequest`
  and `currentRequest` already; OkHttp's `Request` does the same.

## Options

1. **Hook the WebSocket task's own methods** — the message methods record
   the frame (a sent one before the original runs, a received one before the
   app's handler), the response and finish methods end the upgrade, the
   close method records the app's close. Every API (completion handler,
   `async`) is seen; a peer's close is seen only through a pending receive.
2. **Proxy the session's delegate** for
   `urlSession(_:webSocketTask:didOpenWithProtocol:)` and `didCloseWith` —
   public, but `URLSession.shared` has no delegate, the frames are still not
   seen, and ADR-0043 rejected it for the same reasons.
3. **Hook the WebSocket framing below the task** (Network.framework's
   connection) — would see every frame, pings and the peer's close included,
   but it was not probed: none of the task's ivars names a connection, and
   it would be another private layer for three frame kinds.

## Decision

Option 1, with the capture kept on the task under its own key, apart from
the HTTP one, so ADR-0043's connection hooks never see a WebSocket task. A
failure is sent only when the upgrade got no response. The hooks are
installed with ADR-0043's; if any of these methods is missing, WebSocket
capture alone is off, with one log line.

## Consequences

- The captured URL is `http` or `https`, as Android's; the desktop already
  tells a WebSocket from its `101` and its frames.
- A sent frame is recorded when the app calls send, before the bytes leave,
  so an echo is never recorded before it; a send that later fails is still
  recorded.
- Not seen: a socket that drops after the upgrade (no event, Android sends
  `RequestFailed`), a peer's close without a pending receive, the close of
  a bare `cancel()` (DEBT-0038); pings and pongs (DEBT-0029).
- The method names join ADR-0043's under the same proof: macOS host now,
  iOS in M2.12 and M2.13; M2.10 keeps them out of release builds.
