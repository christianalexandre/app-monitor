---
id: DEBT-0108
title: Apps on Kotlin older than 2.3 cannot compile against the SDK
status: Open
date: 2026-10-30
trigger: "M10's plan is written, or an app on Kotlin older than 2.3 asks for the SDK"
paid_date:
---

# DEBT-0108 — Apps on Kotlin older than 2.3 cannot compile against the SDK

## What is missing

The SDK is built with Kotlin 2.4.21 and its classes carry metadata 2.4.0.
A compiler reads at most one version ahead, so an app on Kotlin 2.2 fails
with `was compiled with an incompatible version of Kotlin. The actual
metadata version is 2.4.0, but the compiler version 2.2.0 can read versions
up to 2.3.0`. AGP 9.3.3's built-in Kotlin is 2.2.0: such an app declares
`org.jetbrains.kotlin.android` 2.3 or newer, `apply false`, beside AGP in
its root build (README, ADR-0162).

## Why it was accepted

The SDK uses `kotlin.uuid.Uuid`, stable since Kotlin 2.4 (ADR-0066), and
its dependencies (Ktor 3.6, kotlinx-coroutines 1.11) are built with recent
Kotlin too, so a lower `languageVersion` alone may not be enough. Nobody
measured which floor is reachable, and no app has asked for one older than
2.3.

## Trigger

M10's plan is written, or an app on Kotlin older than 2.3 asks for the
SDK. Then measure the oldest Kotlin that compiles a consumer of
`sdk/core`, `sdk/android-okhttp` and `sdk/ktor` with `apiVersion` and
`languageVersion` lowered in `build-logic/src/main/kotlin/appmonitor.kmp-sdk.gradle.kts`,
and publish that floor.

## How it was paid

> Filled when closing.
