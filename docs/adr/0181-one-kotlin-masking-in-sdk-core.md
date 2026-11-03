---
id: ADR-0181
title: One Kotlin masking in sdk/core, each library resolving its own Location
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0181 — One Kotlin masking in sdk/core, each library resolving its own Location

## Context

`sdk/android-okhttp` masked through `HttpUrl`, `sdk/ktor` on strings by RFC 3986
(ADR-0069 C.1), and each module defined its own `CaptureSink`, `MonitorSink`,
`CapturedBody` and `emit` (DEBT-0055, DEBT-0101). DEBT-0055 held the merge back
because it changes what OkHttp apps see. What it changes was measured, running the old
`Masking` class on `HttpUrl` before moving it:

| Value | Old OkHttp result | String rule alone |
| --- | --- | --- |
| URL `?q=a+b&token=1&r=%41` | `?q=a%20b&token=██&r=A` | `?q=a+b&token=██&r=%41` |
| URL `?%74oken=1` | `?token=██` | `?%74oken=██` |
| `Location: https:\\\\user:pw@h.example\\x?code=1` | `https://██:██@h.example/x?code=██` | user and password **sent** |

The first two only show a URL closer to what went out: masking one field rewrote the
others, so a masked URL differed from the same URL unmasked. The third would have
reduced masking: `HttpUrl` reads a backslash as a slash and OkHttp follows such a
redirect, while the rule's RFC 3986 syntax (SDD 05) sees no authority there.

This is a security and privacy decision. It follows the standing owner decision
**little masking** in `AGENTS.md`: no rule is added, and none is reduced. All types move
behind `InternalAppMonitorApi`, so the public API and each `sdk/*/api` dump are unchanged.

## Options

1. **The rule on strings, nothing else**: the least code, and the third row above
   stops masking credentials OkHttp would follow.
2. **Add backslashes to the rule**: a new rule in the vector, in the Swift SDK too, for a
   value no parser but `HttpUrl` reads this way, against *little masking*.
3. **One rule, the resolver a parameter**: `Masking` in `sdk/core` takes how a
   `Location` is resolved against the request URL. `sdk/ktor` uses the RFC 3986 one,
   `sdk/android-okhttp` `HttpUrl.resolve`, what OkHttp itself follows. The rule is
   written once, and no value is masked less.

## Decision

Option 3, under the standing owner decision *little masking*. `Masking`, `MASK`,
`CaptureSink`, `MonitorSink`, `CapturedBody`, `newRequestId`, `requestFailed` and `emit`
live in `sdk/core`, opt-in by `InternalAppMonitorApi`, which now also applies to
functions and properties.

## Consequences

- A new masking rule is written once for Kotlin; each module's `MaskingVectorTest` still
  runs the vector over what that module reads (`HttpUrl`, `URLBuilder`).
- A masked URL keeps its other fields, and a masked name, as written (`MaskingTest`).
- The two still differ where their libraries do: an OkHttp URL and `Location` are
  normalised by `HttpUrl` before masking, a Ktor one is not. That is what each library
  sends or follows, not a second rule.
- The mock, breakpoint and replay flow is still written twice (DEBT-0101).
