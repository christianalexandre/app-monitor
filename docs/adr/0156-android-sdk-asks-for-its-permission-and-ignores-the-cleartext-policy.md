---
id: ADR-0156
title: The Android SDK asks for its local network permission and no longer stops on the cleartext policy
status: Accepted
date: 2026-10-28
superseded_by:
---

# ADR-0156 — The Android SDK asks for its local network permission and no longer stops on the cleartext policy

## Context

The first field test, a KMP app, needed two files per build type for the
Android SDK alone. The first was a debug network security config with
`cleartextTrafficPermitted` (ADR-0132). The second was a debug
`LocalNetwork.kt`, with a release no-op twin, called from an Activity
(ADR-0017 E). The owner (2026-10-28): adding the dependency and starting
the SDK must be the whole setup.

- The client opens its `ws://` with Ktor CIO, whose sockets never consult
  Android's `NetworkSecurityPolicy` (ADR-0023). The SDK checked the policy
  itself and stopped for good when it said no (ADR-0023 2(b), ADR-0017 F).
  Measured on 2026-10-28 on an API 37 emulator: with that check removed and
  `samples/android` built without any network security config (its
  `targetSdk` forbids cleartext by default), the client reached the
  headless server over `ws://10.0.2.2` and delivered 14 envelopes. The
  app's own OkHttp calls to `http://10.0.2.2` failed with `CLEARTEXT
  communication … not permitted`, as the policy says.
- `Activity.requestPermissions` (API 23) needs no androidx. The SDK's
  `start(context)` already gets the `Application`.

## Options

1. **Keep both steps documented** (ADR-0017 E/F, ADR-0132). Two files per
   build type in every app.
2. **The SDK does both.** `start` registers `ActivityLifecycleCallbacks`.
   On Android 17+, without the permission, the first Activity that stays
   resumed for 500 ms asks for `ACCESS_LOCAL_NETWORK`, once per process: the
   callbacks are removed when it asks, because a dismissed dialog resumes the
   Activity and asking again would loop. A splash or trampoline Activity
   paused before then never asks, so its dialog is not lost with it. The cleartext check is removed: the
   policy governs the app's traffic, not the SDK's own socket.

## Decision

Option 2, under the standing owner decisions "Short setup" (it removes two
steps) and "Debug only" (only the debug SDK opens the socket; the no-ops
declare and ask nothing). It replaces ADR-0017 E (the app asks) and F (a
documented config, an error naming it), and ADR-0023 2(b).

## Consequences

- The README's Android setup loses the network security config and the
  permission request. `samples/android` and `samples/kmp` drop
  `LocalNetwork.kt`. They keep a debug config allowing cleartext to
  `10.0.2.2` and `127.0.0.1` only, for their own calls to
  `tools/e2e-target`.
- Checked on an API 37 emulator: the dialog shows on a fresh install, and
  is not asked again after "Don't allow". With another app's screen opened
  200 ms after launch, it is not shown there; it is shown once the app is
  back.
- Denied: discovery falls back to the emulator host or a manual host, as
  before. The SDK never asks again in that process.
- An app whose policy forbids cleartext still has the SDK reach the
  desktop. That is the point; the SDK exists in debug builds only.
- Supersedes ADR-0132.
