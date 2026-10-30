---
id: DEBT-0083
title: Ktor mocks build their call with Ktor's internal API
status: Open
date: 2026-10-25
trigger: The first PR that changes `ktor` in `gradle/libs.versions.toml`
paid_date:
---

# DEBT-0083 — Ktor mocks build their call with Ktor's internal API

## What is missing

`sdk/ktor`'s `MockAnswer.kt` builds a mocked `HttpClientCall` with
`HttpClientCall(client, requestData, responseData)`,
`takeFromWithExecutionContext` and the builder's `body` setter, all
`@InternalAPI` in Ktor 3.6.0 (ADR-0115). A response a breakpoint edits
is built the same way (`builtCall`), its streamed body read through
`HttpResponse.rawContent`, also `@InternalAPI` (ADR-0123 E.1). So is a
saved body's capture in `Capture.kt`: `readRawBytes()` copies it whole
twice, `rawContent` read up to the limit once. Ktor may
change them in any release without a deprecation. Symptom: a mocked or
edited Ktor call fails, or the SDK stops compiling, after a Ktor
upgrade.

## Why it was accepted

Ktor has no public way to make a call from request and response data
(ADR-0110 D.1). `MockAnswerTest` runs on the JVM, the Android host and
the simulator, so a break shows in the upgrade's own PR; so does
`BreakpointTest`.

## Trigger

The first PR that changes `ktor` in `gradle/libs.versions.toml`: check
the four APIs in the new sources, then re-trigger on the next upgrade
or pay it with a public API if one exists.

## How it was paid

> Filled when closing.
