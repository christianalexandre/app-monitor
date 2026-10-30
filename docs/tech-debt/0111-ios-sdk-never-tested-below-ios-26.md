---
id: DEBT-0111
title: The iOS SDK is tested on iOS 26 and macOS only, never on the iOS 15 it supports
status: Open
date: 2026-10-30
trigger: PLAN-0015 H5.6 starts, or the next PR that changes `sdk/ios/Sources/AppMonitor/Mocks.swift`, `sdk/ios/Sources/AppMonitor/Breakpoints.swift` or the platforms of `Package.swift`
paid_date:
---

# DEBT-0111 — The iOS SDK is tested on iOS 26 and macOS only, never on the iOS 15 it supports

## What is missing

`Package.swift` declares `.iOS(.v15)`. `swift test` runs on the macOS host, and `make swift-sim`
and the end to ends on the one installed simulator runtime, iOS 26.5. Nothing runs the SDK on
iOS 15 to 18. The H4 field test found what that hides: on an iPhone 8 with iOS 16.7.16, every
mocked call ended the app with `-[AppMonitor.MockProtocol task]: unrecognized selector`, which
`URLProtocol.task` raised. `HoldProtocol` read it the same way. Both now keep the task given to
`init(task:cachedResponse:client:)`, and the fix was proven on that iPhone only, by hand.

## Why it was accepted

No iOS 16 simulator runtime is installed, and whether Xcode 26 runs one is not checked. The
iPhone 8 proves the fix, but it is attached by the owner, not by the loop or CI.

## Trigger

PLAN-0015 H5.6 starts, or the next PR that changes `Mocks.swift`, `Breakpoints.swift` or the
platforms of `Package.swift`. Then the SDK's tests, mocks and breakpoints included, run on the
lowest iOS it declares, or that floor is raised to what is tested.

## How it was paid

> Filled when closing.
