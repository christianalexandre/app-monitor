---
id: ADR-0100
title: What the connection layer captures on iOS, and when it is on
status: Proposed
date: 2026-10-25
superseded_by:
---

# ADR-0100 — What the connection layer captures on iOS, and when it is on

## Context

PLAN-0009 M8.1 (b). ADR-0004 layer 3 is "host, port, bytes, timing and
errors of every connection via rebinding of dynamic symbols
(`getaddrinfo`, `connect`, `nw_connection_*`)", in debug builds only,
"shown in the desktop as opaque TLS connections". What it captures is
"stop and ask" 4 (security / privacy): it reports sockets the app's own
code did not ask the SDK to watch, third-party SDKs' included. Whether
it is on by default touches `AppMonitorOptions`, public API.

Evidence:

- fishhook (`facebook/fishhook`, BSD-3-Clause, last commit 2021-10-12,
  its iOS 15 crash fix) rebinds a symbol in the lazy and non-lazy
  pointer tables of every loaded image. A call made inside a system
  library does not go through those tables: in fishhook issue #23, a
  rebound `connect` is not reached when `libsystem_network` calls
  `__connect` itself, and the maintainer's answer is that such calls
  "won't go through the tables that fishhook hooks". So code in the app
  binary or an embedded framework (gRPC-Core and its statically linked
  BoringSSL, ADR-0004) is reached; `URLSession` and Network.framework's
  own sockets are not, unless the app calls `nw_connection_*` itself.
- ADR-0004 also says: "Which SDK the previous tool missed is still
  unverified — confirm with the Instruments Network template". Only the
  owner has the app and the old tool; the agent cannot run that check.

Already decided, not reopened here: debug only (ADR-0048, standing);
layer 3 exists (ADR-0004); `capture_layer = CONNECTION` (ADR-0038).

## Options

### A. Which symbols, what each reports

1. **`getaddrinfo`, `connect`, `send`/`write`, `recv`/`read`, `close`
   on sockets, and `nw_connection_create` / `_start` / `_cancel` with
   their state handler.** One exchange per connection: host name when a
   prior `getaddrinfo` resolved it (else the address), port, bytes each
   way, open and close times, and the `errno` or `nw_error` of a failed
   connect. No payload: the bytes are TLS. Byte counts need the
   read/write family, the hottest calls in a process.
2. **Only `getaddrinfo`, `connect`, `close` and `nw_connection_*`
   state.** Host, port, timing, errors; no byte counts, nothing on the
   per-read path.

Recommended: 1, with the read/write wrappers doing a table lookup and two
adds per call; M8.3 measures their cost against a raw socket loop.

### B. When it is on

1. **On with `start()` in debug builds, `AppMonitorOptions.
   captureConnections` (default `true`) turns it off.** No setup step
   (standing "Short setup"); a developer who sees a crash or slowdown
   from rebinding has one switch.
2. **Off by default, `captureConnections: true` to turn it on.** Safer
   against rebinding surprises; a setup step for the case layer 3 was
   built for, and a developer who does not know of it never sees the
   missed SDK.

Recommended: 1.

### C. What it does not report

1. **Its own socket to the desktop, sockets to the loopback address,
   and, should rebinding ever reach one, a connection a `URLSession`
   task captured by layer 1 already reported** (same host and port
   within the task's lifetime).
   Every other connection is reported, third-party SDKs' included, as
   metadata only.
2. **Only connections to hosts on an allow list in the options** — less
   noise, and the SDK the developer does not know about is the one not
   on the list.

Recommended: 1.

### D. The owner's Instruments finding

Which SDK the old tool missed, and whether it uses gRPC-Core (layer 3
then 4) or `URLSession` in a background or custom way (layer 1). The
plan's order does not depend on the answer; layer 3's tests add a case
shaped like that SDK if it is not a plain socket client.

## Decision

Empty while `Proposed`.

## Consequences

- A.1 wraps calls every socket in the process makes; M8.3 records the
  cost per call and the raw-socket test pins that wrapped calls still
  behave.
- `URLSession` traffic is reported by layer 1 only; layer 3 sees what is
  outside it, which is its purpose.
- B.1 adds an option to `AppMonitorOptions`, mirrored in the release
  stubs and the API dump (ADR-0048).
