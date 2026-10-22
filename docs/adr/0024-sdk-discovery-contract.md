---
id: ADR-0024
title: One mDNS selection rule for every client, and the SDK's discovery sources
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0024 — One mDNS selection rule for every client, and the SDK's discovery sources

## Context

PLAN-0002 M1.6 writes `sdk/core` discovery and the `AppMonitor.start` /
`stop` entry point (SDD 04). Already decided, not reopened here: order
manual host → mDNS → `10.0.2.2` on the emulator (ADR-0002); a manual host
"skips discovery" (ADR-0017 B); without `ACCESS_LOCAL_NETWORK` mDNS is
skipped with one log line (ADR-0017 E); the client gives discovery 5 s
(ADR-0022); `Hello` identity (ADR-0018 D).

The fake client's discovery carries three known flaws (DEBT-0009,
DEBT-0011): the first resolve wins even before its TXT record, a blocking
`requestServiceInfo` runs on the JmDNS listener thread, names are compared
by case. The SDK needs the same rules on Android.

Evidence, checked on 2026-10-22:

- `NsdManager` (`sources/android-37.0`, `api-versions.xml`):
  `resolveService` is deprecated in 34 — "may not contain some service
  information that could be delivered later" — and before 34 runs one
  resolve at a time (`FAILURE_ALREADY_ACTIVE`).
  `registerServiceInfoCallback(NsdServiceInfo, Executor, …)` and
  `getHostAddresses()` exist since 34 and deliver repeated
  `onServiceUpdated` calls. `discoverServices` takes the type without the
  `.local.` domain. `ACCESS_LOCAL_NETWORK` is
  `"android.permission.ACCESS_LOCAL_NETWORK"`, since 37; the project
  compiles against 36, so it is referenced by string.
- JmDNS 3.6.3 `requestServiceInfo(type, name, timeout)` waits for the
  answer on the calling thread (DEBT-0011).
- DNS names, DNS-SD instance labels included, compare case-insensitively
  (RFC 4343).
- The `Medium_Phone` emulator (API 37): `getprop ro.hardware` is `ranchu`,
  `ro.kernel.qemu` is `1`; `Build.HARDWARE` reads `ro.hardware`. `goldfish`,
  the older emulator board name, is accepted too but not observed here.

## Options

1. **Where the selection rule lives**: (a) once per client — the SDK and the
   fake client drift, as the LAN filter did (DEBT-0005); (b) a pure
   `DesktopSelector` in `protocol`, the contract module every client
   already depends on; each client only feeds it answers and runs the TXT
   grace timer.
2. **Which desktop among several**: (a) the first one fully resolved (TXT
   included) — the choice depends on packet timing; (b) the first one
   answered with an address is kept, every other name ignored for that
   round; its path waits 500 ms for a TXT record, then the default.
3. **mDNS budget on the emulator**: (a) all of the 5 s — the `10.0.2.2`
   fallback never runs inside the client's bound; (b) 3 s, then `10.0.2.2`;
   on a device mDNS has the whole 5 s.
4. **Android resolve**: (a) `resolveService` everywhere — deprecated, may
   miss a late TXT; (b) `registerServiceInfoCallback` from 34, serialized
   `resolveService` below.

## Decision

1(b), 2(b), 3(b), 4(b). A manual host is the only source. A failing mDNS
browser is logged and treated as "found nothing". `Hello` is built lazily on
the client's scope (it reads the device id file), so `start()` on the main
thread does no disk I/O. The emulator is `Build.HARDWARE` `ranchu` or
`goldfish`.

## Consequences

- The fake client uses the same selector and resolves off the listener
  thread: DEBT-0009 and DEBT-0011 are paid. The iOS SDK (M2) applies the
  same rules in Swift.
- `NsdBrowser`, the permission check and the emulator test run only on a
  device or emulator; host tests drive discovery through fake browsers.
  PLAN-0002 M1.11 and M1.12 exercise them.
- Every round on the emulator costs up to 3 s of mDNS before `10.0.2.2`.
- `AppMonitorOptions.appName` is accepted but not sent: `Hello` has no field
  for it (DEBT-0024).
