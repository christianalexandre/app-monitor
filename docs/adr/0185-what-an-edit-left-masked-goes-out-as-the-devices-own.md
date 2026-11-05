---
id: ADR-0185
title: What an edit left masked goes out as the device's own
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0185 — What an edit left masked goes out as the device's own

## Context

ADR-0111 C.1 has a resume carry only the fields the developer changed, so a
field left alone keeps the device's value. But its unit is the whole field.
`resumeOf` (`HeldCalls.kt`) sends the whole URL when any part of it is edited,
and every value of a header name when one value is edited (`set_headers` replaces
the name, SDD 12). A masked token left in place then went out as the literal
`██`: DEBT-0088, where it waited for the next change to `resumeOf`. PLAN-0016 H6
does not let that trigger wait again.

The window's own label ("██ left as is keeps the device's value") already promises
this for a header. Nothing the device sends changes: every event
is masked as before, so what the desktop stores and shows is the same. Only what
goes out to the server the app was already calling changes, which is what the
developer asked for by leaving the value alone.

## Options

1. **Each SDK puts its own value back.** `Masking` holds the rule and the real
   request is in hand. No protocol change, and the desktop still never holds a
   secret.
2. **The desktop sends a diff of the URL.** `BreakpointResume` gets query edits
   by field. A protocol change on four sides for what the device can read off
   the string, and a field added by hand next to a masked one is the same case.
3. **Refuse an edit that leaves a mask.** The call fails, and the developer must
   turn masking off to edit a URL with a token.

## Decision

Option 1, `Masking.restoredUrl` and `restoredHeaders` in `sdk/core` and
`restoredURL` and `restoredHeaders` in `sdk/ios`, pinned by the `restores` of
`protocol/fixtures/masking.json`. It follows the standing owner decision *Little
masking* (AGENTS.md): no new masking rule, nothing new leaves the device towards
the desktop.

- **What goes back.** A user name or password that is exactly `██`; a query or
  fragment field that is exactly `name=██`, `name` being masked (a query's bare
  `name` goes back bare); a header of a masked name whose value is exactly `██`;
  those same URL parts in a `Location` or `Content-Location` value.
- **Which value.** A name's nth edited value takes the device's nth of that name.
  None there, the `██` stays and goes out as typed. An unmasked name, or
  `masking = false`, keeps what was typed.
- **A refused edit's reason** quotes the URL as it came from the desktop, never the
  restored one: that reason is a `RequestFailed` the desktop stores.
- **A URL edited to another host** takes the token with it, as an `Authorization`
  left alone already does (ADR-0111 C.1). The edit is the developer's.

Ktor's `takeFrom(String)` adds the string's query to the builder's and keeps a fragment
or user info the edit dropped, so an edited URL went out with both queries (found by
this task's test): the edited URL is parsed whole (`Url`) and replaces the builder's.

## Consequences

- A URL or a header group can be edited without knowing or retyping the secret in it.
- By place, not by value: of three `Set-Cookie` values shown as `██`, deleting the first
  keeps the first two. The desktop cannot say which one went, the values being the
  same `██`. On iOS a name holds one value (`headerFields`), so this does not arise.
- A literal `██` in a masked place cannot be sent when the device has a value
  there: turning masking off is the way (ADR-0034).
