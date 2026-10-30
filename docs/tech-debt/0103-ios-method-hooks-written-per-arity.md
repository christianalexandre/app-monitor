---
id: DEBT-0103
title: The iOS SDK writes each method hook by hand
status: Open
date: 2026-10-30
trigger: "A new Foundation or CFNetwork method hooked in `sdk/ios/Sources/AppMonitor/`, or a hook in `URLSessionCapture.swift`, `WebSocketCapture.swift` or `BackgroundCapture.swift` that breaks on a new iOS version"
paid_date:
---

# DEBT-0103 — The iOS SDK writes each method hook by hand

## What is missing

Each Objective-C method the Swift SDK replaces (ADR-0043, ADR-0045,
ADR-0046) has its own `hook*` function. Each one has a `typealias
Original = @convention(c) …`, an `unsafeBitCast` of
`method_getImplementation`, a block of the same arity, and
`method_setImplementation`. That is `hook`, `hookObject`, `hookResume`
and `hookUpload` in `URLSessionCapture.swift`, `hookTwo`,
`hookReceive` and `hookClose` in `WebSocketCapture.swift`, and
`hookData`, `hookDownload` and `hookComplete` in
`BackgroundCapture.swift`. A wrong arity or cast in a new one crashes at
the first call, and nothing shares the reentrancy guard `hookObject`
has.

## Why it was accepted

Swift cannot be generic over a `@convention(c)` function's arity, so
part of the repetition is forced. Each hook is a few lines next to the
method it replaces and its tests (`URLSessionCaptureTests`,
`WebSocketCaptureTests`, `BackgroundTests`). A table of selectors and
arities would hide which private method each one relies on.

## Trigger

A new Foundation or CFNetwork method hooked in
`sdk/ios/Sources/AppMonitor/`, or a hook in
`sdk/ios/Sources/AppMonitor/URLSessionCapture.swift`,
`sdk/ios/Sources/AppMonitor/WebSocketCapture.swift` or
`sdk/ios/Sources/AppMonitor/BackgroundCapture.swift` that breaks on a
new iOS version. Then one helper per arity (one, two, three object
arguments and the background replies) with the reentrancy option goes
into one file, and every hook uses it.

## How it was paid

> Filled when closing.
