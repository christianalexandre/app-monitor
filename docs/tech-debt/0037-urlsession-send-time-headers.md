---
id: DEBT-0037
title: Headers CFNetwork adds at send time are not captured on iOS
status: Open
date: 2026-10-23
trigger: M2.13 compares the desktop with a server log of the same request, or a developer reports a header missing from an iOS request
paid_date:
---

# DEBT-0037 — Headers CFNetwork adds at send time are not captured on iOS

## What is missing

The iOS SDK records a request's headers from `currentRequest` when the task
first resumes (ADR-0043). Headers CFNetwork adds later are not there:
`Cookie` from the session's `HTTPCookieStorage`, `User-Agent`,
`Accept-Encoding`, `Accept-Language`, and the default `Content-Type` of a
body. The desktop shows fewer headers than the server receives; a cookie
the server got never reaches the desktop, masked or not.

## Why it was accepted

ADR-0036 A asked M2.7 to say whether the capture sees the stored cookie; it
does not. Reading the headers on the wire needs another private hook into
CFNetwork's request serialisation, or rebuilding them from the cookie store
and the configuration, which can disagree with what was sent. Session
`httpAdditionalHeaders`, where apps put their tokens, are captured.

## Trigger

M2.13 compares the desktop with a server log of the same request, or a
developer reports a header missing from an iOS request.

## How it was paid

> Filled when closing.
