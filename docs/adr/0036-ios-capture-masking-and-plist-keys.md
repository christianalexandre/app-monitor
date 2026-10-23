---
id: ADR-0036
title: What the iOS SDK captures and masks, and the Info.plist keys it needs
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0036 — What the iOS SDK captures and masks, and the Info.plist keys it needs

## Context

The iOS SDK copies an app's `URLSession` traffic to the desktop over plain
`ws://` on the LAN. What it captures, masks and requires from the app is a
"stop and ask" area (`AGENTS.md`, security and privacy). Its public API is
ADR-0035. The owner sees the SDK as a debug-only tool and asked for a way to
see 100% of the traffic (ADR-0034).

Already decided, not reopened here:

- Masking runs on the device, before the wire, and every SDK implements the
  same rule (ADR-0018 A): `Authorization`, `Proxy-Authorization`, `Cookie`,
  `Set-Cookie` plus `maskedHeaders` → `██`, name kept; `maskedQueryParameters`
  in the query and in a `name=value&…` fragment; URL user name and password
  `██`; `Location` and `Content-Location` resolved and masked as URLs; bodies
  never masked (ADR-0018 B, ADR-0032, ADR-0033, DEBT-0019, SDD 05).
- `masking = false` turns every rule off (ADR-0034).
- 1 MiB per body, configurable; unreadable bodies sent empty with a marker
  (ADR-0018 C); WebSocket frames cut the same way (ADR-0028).
- Device approval per (`device_id`, `app_id`) on the desktop; a device
  trusts any `_appmonitor._tcp` it finds (ADR-0018 E, F; DEBT-0020).

Evidence, checked on 2026-10-23:

- TN3179 (<https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy>):
  "Making an outgoing TCP connection" to a local network address and every
  Bonjour operation (browse, resolve) require local network access; the app
  adds `NSLocalNetworkUsageDescription` and lists its service types in
  `NSBonjourServices`. The multicast entitlement is needed for "arbitrary
  Bonjour service types" or "browsing for all advertised service types",
  not for one named type. "The simulator doesn't support local network
  privacy. Test your local network privacy behavior on a real device."
- `NSAllowsLocalNetworking`: "In iOS 17 … ATS no longer allows connections
  to IP addresses by default" (Apple documentation JSON,
  `bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking`).
  ATS governs `URLSession`; ADR-0003 builds the transport on
  Network.framework.
- `UIDevice.name`: "iOS 16 and later: Generic device name, "iPhone""; the
  user-assigned name needs an entitlement (Apple documentation JSON,
  `uikit/uidevice/name`).
- `URLSessionConfiguration.protocolClasses`: "You cannot use custom
  `URLProtocol` subclasses in conjunction with background sessions"
  (Apple documentation JSON); background sessions are M2.9.
- The previous tool's README injects `NSLocalNetworkUsageDescription` and
  `NSBonjourServices = [_appmonitor._tcp]` into the built `Info.plist` from
  a Run Script phase, debug configuration only.

## Options

### A. Where a secret can sit, and what happens to it

| Place | Rule (option A.1) |
| --- | --- |
| Request headers, also those from `httpAdditionalHeaders` | Header rule |
| `Cookie` added from `HTTPCookieStorage` | Header rule, when the capture sees it (M2.7 states whether it does) |
| URL user info, query, fragment | URL rule |
| Response headers, `Set-Cookie`, `Location`, `Content-Location` | Header and URL rules |
| WebSocket handshake (`Sec-WebSocket-Protocol` can carry a token) | Header rule; not masked by default |
| WebSocket frames | Not masked, cut at `maxBodyBytes` |
| Request and response bodies, upload files | Not masked (DEBT-0019) |
| `URLCredential` in auth challenges, client certificates | Never captured |

1. **The Android rule, member for member** (table above), pinned by a
   shared test vector; `masking = false` turns it off. No rule exists on one
   SDK only.
2. **Option 1 plus iOS-only rules** (`Sec-WebSocket-Protocol`, tokens in
   frames) — the two SDKs then show the same traffic differently, and the
   owner asked for fewer barriers, not more.

Recommended: 1.

### B. Which side the default errs on

1. **`masking = true` by default**, as Android (ADR-0034 option 1): a
   developer who adds the SDK and nothing else does not put bearer tokens
   in the desktop's SQLite and MCP (M5, M6).
2. **`masking = false` by default on both SDKs** — 100% of the traffic with
   no setup. Changes a decision already shipped on Android: a new ADR
   superseding ADR-0034, and an Android PR.

Recommended: 1, so iOS ships the Android behaviour; if the owner prefers 2,
it is one ADR for both SDKs, not an iOS exception.

### C. Bodies

1. **Same limit and markers as Android**: data uploads and response bodies
   copied up to `maxBodyBytes`; a file upload reads the first
   `maxBodyBytes` of the file (no side effect on the app's call); a stream
   upload (`httpBodyStream`, `uploadTask(withStreamedRequest:)`) is sent
   empty with `body_truncated`, never consumed.
2. **Option 1, but file uploads empty too** — no file read by the SDK, less
   to show.

Recommended: 1.

### D. Device identity

1. **Random UUID on first `start()`, in a file under Application Support
   marked `isExcludedFromBackup`** — stable per install, not restored onto
   another device, as Android's no-backup storage (ADR-0018 D).
   `device_name` is the model identifier (`utsname.machine`, e.g.
   `iPhone16,2`; on the simulator `SIMULATOR_MODEL_IDENTIFIER` plus
   " Simulator"): needs no entitlement, while `UIDevice.name` is "iPhone".
2. **`identifierForVendor`** — shared by every app of the same vendor, so
   two apps look like one device to the approval.
3. **UUID in `UserDefaults`** — restored from a backup onto a new phone:
   two devices with one id.

Recommended: 1.

### E. Info.plist keys

1. **`NSLocalNetworkUsageDescription` and `NSBonjourServices =
   [_appmonitor._tcp]`, debug builds only**, added by the build-phase script
   of M2.11. No multicast entitlement (one named type). No ATS key while
   the transport is Network.framework (ADR-0003); if M2.4 chooses
   `URLSessionWebSocketTask`, `NSAllowsLocalNetworking` is needed for
   IP hosts on iOS 17+, and relaxes ATS for the app's own local traffic
   too — M2.4 must say so.
2. **The app adds the keys by hand to its `Info.plist`** — they then ship
   in release and show a permission string for a tool the release does not
   contain.

Recommended: 1. Without the keys on a device, Bonjour fails: the SDK logs
one line naming them and uses the manual host (ADR-0017 E's pattern).

## Decision

The owner accepted every recommendation: A.1 (the Android rule, member for
member, pinned by a shared test vector), B.1 (`masking = true` by default),
C.1 (Android's body limit and markers, streams sent empty), D.1 (random UUID
excluded from backup, model identifier as `device_name`) and E.1 (plist keys
added to debug builds only by the M2.11 script).

## Consequences

- The shared masking test vector (ADR-0018 A) becomes a fixture both SDKs
  read; M2.7 adds it.
- Local network behaviour is only provable on a device (TN3179): M2.13's
  physical iPhone step is required, not optional.
- Background sessions cannot use `URLProtocol`; M2.9 captures them through
  delegate proxying, under the same rules.
