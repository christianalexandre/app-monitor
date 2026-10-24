---
id: ADR-0077
title: Hello names the SDK that opened the session
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0077 — Hello names the SDK that opened the session

## Context

ADR-0075 makes `make e2e-kmp-ios` fail with fewer than two sessions of the
KMP sample, so that "each exchange once" is not vacuous without the Swift
SDK's session. The first M4 milestone review (PLAN-0005, M4.R1) found the
count blind: `sdk/core` and the Swift SDK send the same `Hello` field for
field (`IosDevice.kt` and `Device.swift`: same device id file, model, OS
format, `sdk_version` `0.1.0-dev`), so one `sdk/core` session that
reconnects counts as two and passes with no Swift session at all.

Nothing on the wire tells the two apart. `Hello` is a proto3 message
(ADR-0001); a new field number is wire-compatible both ways, an old desktop
ignores it and an old SDK sends it empty.

## Options

1. **Add `string sdk_name = 10` to `Hello`** — `"kotlin"` from `sdk/core`
   (Android and iOS), `"swift"` from `sdk/ios`; the headless server prints
   it on its `connected:` line. One field, both SDKs, the fixture and
   `Samples` on both sides. The desktop can later show it.
2. **Make `sdk_version` differ per SDK** (e.g. `0.1.0-dev-swift`) — no
   protocol change, but overloads a version string with an identity the
   desktop would have to parse.
3. **Count from the device log** (`NSLog` lines of each SDK) — no protocol
   change, but ties the assertion to log wording and the simulator's log
   store, which `e2e-kmp-ios` only dumps after the run.

## Decision

Option 1. `Hello.sdk_name` names the SDK implementation, not the capture
adapter: `sdk/ktor` and `sdk/android-okhttp` both run on `sdk/core` and
send `"kotlin"`. `scripts/e2e_lib.sh` counts sessions per `sdk_name`, and
`e2e-kmp-ios` needs one of each.

## Consequences

- A third SDK picks its own `sdk_name`; the value is part of the contract
  `protocol/fixtures/hello.bin` pins.
- Not a privacy change: it names the library, nothing of the app or user.
- An empty `sdk_name` is a desktop talking to an SDK older than this ADR;
  `sessions` in `scripts/e2e_lib.sh` does not count it.
