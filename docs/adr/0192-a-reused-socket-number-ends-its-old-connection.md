---
id: ADR-0192
title: A reused socket number ends its old connection at the next connect
status: Accepted
date: 2026-11-04
superseded_by:
---

# ADR-0192 — A reused socket number ends its old connection at the next connect

## Context

DEBT-0081: in `connections.c` a socket's slot (`counted[fd]`) is cleared
only by the rebound `close`. A socket closed any other way, by `dup2` onto
its number or by an image the rebinding skips (ADR-0100: the shared cache),
keeps its slot. The next `connect` on that number is then taken for a
connect again on the same socket (`EALREADY`, `EISCONN`), is not reported,
and its bytes are added to the old exchange, which ends only when the new
socket closes.

Already decided, not reopened here: ADR-0100's rebinding and image filter,
ADR-0103's exchange per socket.

Evidence, `swift test --package-path sdk/ios --filter ConnectionCaptureTests`
on the macOS host, 2026-11-04:

- A blocking `connect` and a non-blocking one past `EINPROGRESS` both leave
  the socket with its own local port (`getsockname`): the existing
  `nonBlockingConnectIsCountedOnceFromInProgress`, which connects again on
  the same socket, still sees one exchange with the check below.
- A new socket has no local port (0) until it connects or binds.
- A second `connect` on the same connected socket returned `0` as well as
  `EISCONN` (the same test accepts both), so the call's result cannot tell
  a new socket from the same one.

## Options

1. **Rebind `dup2` and `dup3` too.** Ends the slot when `dup2` closes it.
   Does not cover a socket closed by a system image, which the rebinding
   never sees.
2. **Compare the local port at `connect`.** The slot keeps the port its
   socket had after `connect`; a `connect` on a counted number whose socket
   now has another port (0 for a new one) ends the old exchange with its
   counts, then counts the new socket. Covers any closer; one `getsockname`
   per `connect` on a counted number only, none on the others.
3. **Leave it.** The merged exchange is wrong data each time it happens.

## Decision

Option 2. The old exchange ends as `ResponseReceived` with what it moved:
its socket is gone, so no `SO_ERROR` can be read from it.

## Consequences

- `aSocketClosedOutsideCloseEndsWhenItsNumberConnectsAgain` closes a
  counted socket with `dup2` and connects the number again: two exchanges,
  each with its own bytes. It fails on the earlier code.
- A socket closed outside `close` and never followed by a `connect` on its
  number still ends only when the number is closed. Its exchange stays
  open, as before.
- A socket whose non-blocking connect failed, connected again by the app,
  may have lost its local port: it ends as `ResponseReceived` with no bytes,
  and the retry is a new exchange.
