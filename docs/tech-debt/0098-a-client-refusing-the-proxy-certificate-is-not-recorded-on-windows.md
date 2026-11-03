---
id: DEBT-0098
title: A client refusing the proxy's certificate is not recorded on Windows, and once on Ubuntu
status: Open # Open | Paid | Dropped
date: 2026-10-26
trigger: Someone runs the proxy on Windows, this test fails again on Ubuntu, or the next PR that changes how `ClientHandler.kt` handles the client's TLS handshake
paid_date:
---

# DEBT-0098 — A client refusing the proxy's certificate is not recorded on Windows, and once on Ubuntu

## What is missing

`ProxyServerTest`'s "a client refusing the proxy certificate is a failed
exchange, not a hang" passes on macOS and Ubuntu and fails on the release
gate's `windows-latest` (#295's runs, the last 40427800077). There curl
(MSYS2's native 8.23, OpenSSL 3.6) reaches the proxy, gets its leaf and
refuses it — `curl: (60) … self-signed certificate in certificate chain` —
and exits; the proxy stores nothing at all for the CONNECT, not even an
open exchange. A client that closes before any TLS byte is recorded on
Windows (the test beside it passes there), so the CONNECT capture does
start and end on a close.

Not confirmed: either Windows drops the client's TLS alert with the reset
that follows it, and the proxy takes the handshake as done (the CONNECT
capture is let go on a successful handshake, and an empty tunnel records
nothing), or the server side's handshake completes before the alert.

The test is skipped on Windows (`assumeFalse`, naming this DEBT); curl does
not hang, the gap is only that the refusal is not shown on Windows.

The same test failed once on `ubuntu-latest` (2026-11-03, CI run 41664133951,
#343, which touches no proxy code; the only red among the last 40 CI runs):
curl exited with its `(60)`, `ended(1)` timed out after 10 s, and `stored()`
then read `none`. So the skip on Windows does not fence the failure in.

- The timeout's stack is in `Storage.flush`, not in the loop's `delay(50)`, so
  the store's writer may have been the slow part rather than the proxy. One
  sample; not confirmed. The report's stderr holds nothing from this test, and
  the tests after it took their usual time.
- Not reproduced: 300 runs in a row on macOS with probes, each got
  `SSLHandshakeException: (unknown_ca)`, then the failed exchange. No Linux
  container was tried, the Docker daemon being down, and that is the next step:
  repeat the test on 2 CPUs, as H5.2d did.

## Why it was accepted

Confirming the cause needs a Windows machine to trace the handshake; the
gate is the only one. The proxy is a debug tool run by a developer, the
miss shows nothing wrong, and every other proxy test passes on Windows.

## Trigger

Someone runs the proxy on Windows, this test fails again on Ubuntu, or the
next PR that changes how `ClientHandler.kt` handles the client's TLS handshake.
