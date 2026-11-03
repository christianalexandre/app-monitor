---
id: DEBT-0098
title: A client refusing the proxy's certificate not confirmed recorded on Windows
status: Open # Open | Paid | Dropped
date: 2026-10-26
trigger: The release gate's Windows job runs `ProxyServerTest`, no longer skipped there
paid_date:
---

# DEBT-0098 — A client refusing the proxy's certificate not confirmed recorded on Windows

## What is missing

`ProxyServerTest`'s "a client refusing the proxy certificate is a failed
exchange, not a hang" failed on the release gate's `windows-latest` (#295's
runs, the last 40427800077), with curl 8.23 on OpenSSL 3.6 refusing the leaf
with `curl: (60)` and nothing stored for the CONNECT, and was skipped there.

The same failure hit `ubuntu-latest` on 2026-11-03 (runs 41664133951 and
41665849998) and was reproduced on macOS with Homebrew's curl 8.23 on OpenSSL
4.1, same `curl: (60) SSL certificate OpenSSL verify result` line: the proxy saw
a **successful** handshake, then a close, and let the CONNECT capture go on the
success. macOS's own curl 8.7 (SecureTransport) refuses during the handshake.
A tunnel closed before its first request now ends the CONNECT as failed, and the
test runs on Windows again.

What is left: no Windows machine ran it. The same curl version and the same
outcome point at this cause, nothing there proves it.

## Why it was accepted

The loop has no Windows machine and does not dispatch the release gate; its
Windows job is the check, and H6.5's Accept already names it.

## Trigger

The release gate's Windows job runs `ProxyServerTest`, no longer skipped there.
