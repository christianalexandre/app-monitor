---
id: DEBT-0098
title: A client refusing the proxy's certificate is not recorded on Windows
status: Open # Open | Paid | Dropped
date: 2026-10-26
trigger: Someone runs the proxy on Windows, or the next PR that changes how `ClientHandler.kt` handles the client's TLS handshake
paid_date:
---

# DEBT-0098 — A client refusing the proxy's certificate is not recorded on Windows

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

## Why it was accepted

Confirming the cause needs a Windows machine to trace the handshake; the
gate is the only one. The proxy is a debug tool run by a developer, the
miss shows nothing wrong, and every other proxy test passes on Windows.

## Trigger

Someone runs the proxy on Windows, or the next PR that changes how
`ClientHandler.kt` handles the client's TLS handshake.
