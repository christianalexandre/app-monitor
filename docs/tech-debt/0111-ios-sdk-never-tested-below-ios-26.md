---
id: DEBT-0111
title: The iOS SDK is tested from iOS 16 up, never on the iOS 15 it declares
status: Open
date: 2026-10-30
trigger: A Mac on macOS 14 or earlier, or an iOS 15 device, can run the suite, or the platforms of `Package.swift` change
paid_date:
---

# DEBT-0111 — The iOS SDK is tested from iOS 16 up, never on the iOS 15 it declares

## What is missing

`Package.swift` declares `.iOS(.v15)`, and nothing has run the SDK on iOS 15. The lowest iOS it
ran on is 16.0 on the simulator (`SIM_OS=16.0 make swift-sim`) and 16.7.16 on an iPhone 8, by hand.

Each step down found a bug: the iPhone 8 found every mocked call ending the app on
`URLProtocol.task` (#321), and the iOS 16.0 simulator found a `Location` that `URLComponents`
rejects sent unmasked (ADR-0170). iOS 15 may hide more.

## Why it was accepted

ADR-0171, the owner's decision. macOS 26 refuses the iOS 15.0 runtime ("not supported on hosts
after macOS 14.99.0"), and no iOS 15 device is at hand. The floor stays because the owner's own
app targets iOS 15.0.

## Trigger

A Mac on macOS 14 or earlier, or an iOS 15 device, can run the suite: run it there, then close
this. Or the platforms of `Package.swift` change.

Re-triggered on 2026-11-03 by PLAN-0015 H5.6, which fired the first trigger and could not pay it.
Changes to `Mocks.swift` and `Breakpoints.swift` left this trigger: DEBT-0118 covers them.

## How it was paid

> Filled when closing.
