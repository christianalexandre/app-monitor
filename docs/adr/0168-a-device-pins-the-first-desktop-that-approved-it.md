---
id: ADR-0168
title: A device pins the first desktop that approved it
status: Accepted
date: 2026-10-31
superseded_by:
---

# ADR-0168 — A device pins the first desktop that approved it

## Context

PLAN-0015 H5.4, DEBT-0020. ADR-0018 F let the SDK trust any
`_appmonitor._tcp` it finds, until the first public SDK release. What it
sends there is the app's traffic as captured: bodies unmasked, only four
headers masked by default, none with `masking = false` (ADR-0018 B,
ADR-0034). mDNS cannot tell one host from another on the LAN (RFC 6762
§21), and `protocol` `DesktopSelector` keeps the first desktop answered
(SDD 04, *Discovery*).

What exists today, read for this ADR:

- The SDKs already keep one file per install: the device id, in
  `noBackupFilesDir` (Android) or `Application Support` (iOS)
  (`sdk/core` `DeviceId.kt`, SDD 04). A second file beside it costs no
  new storage mechanism.
- The desktop's TXT record holds `v` and `path` only
  (`desktop/server` `MdnsAdvertiser.kt`); `HelloAck` holds
  `protocol_version`, `session_id` and `channels` (`core.proto`). Nothing
  names the desktop.
- `HelloAck` is sent only after the desktop approved the device
  (ADR-0018 E, ADR-0083): it is the first moment the device knows a
  developer chose this pairing on that desktop.
- The transport is plain `ws://` (ADR-0018). An attacker who can redirect
  or read the device's TCP on the LAN reads its traffic whatever the device
  pins; that exposure is not reopened here.

Two cases are in scope, both met in practice on a shared LAN:

1. **A colleague's desktop**: several developers on one office network,
   each running the desktop; a device sends its traffic to whichever
   answers first.
2. **A host advertising the service where the developer's desktop is not**
   (café, conference Wi-Fi): it receives everything the app sends.

The decision is about what the device exposes and to whom, and it changes
what a developer sees when they move to another desktop: a "stop and ask"
area (`AGENTS.md`, security / privacy). No standing owner decision covers
which desktop a device trusts, so this ADR waits for the owner.

## Options

1. **Pin the first desktop that approved the device, by id.** The desktop
   creates a random `desktop_id` (UUID) once and keeps it in its store's
   settings; it advertises it in the TXT record (key `id`) and sends it in
   a new optional `HelloAck.desktop_id`. The SDK writes the id of the first
   `HelloAck` that carries one beside its device id. From then on
   discovery skips answers whose `id` differs, and a `HelloAck` with
   another id closes the session on the device and backs off. A manual
   `host` neither checks nor pins: the developer named that desktop. A
   desktop that sends no id (older desktop, headless `make server` unless
   given one) is not pinned. Moving to another desktop: clear the app's
   data or reinstall, which resets the device id too, or set `host`.
   Protects case 1, and case 2 for every device that already met its
   desktop (the id is broadcast only where that desktop runs). Does not
   protect the first connection, nor against a host on the desktop's own
   LAN that copies the id from its TXT, the same host that could already
   read plain `ws://`. No setup step, no public API change.
2. **Option 1 with a key instead of an id.** The desktop keeps a P-256
   key pair; `Hello` carries a nonce, `HelloAck` the public key and a
   signature of the nonce; the device pins the key. A copied id no longer
   passes, but a host that relays the plain `ws://` connection to the real
   desktop still does, so the gain over 1 is small until the transport is
   TLS. Costs signature code in `sdk/core` (JVM, Android, Kotlin/Native)
   and `sdk/ios` (CryptoKit) and a key on the desktop.
3. **Pairing code.** The desktop shows a code; the developer passes it to
   the SDK (`AppMonitorOptions`, or a screen the SDK shows). Protects the
   first connection too. Adds a setup step and public API, against the
   standing *Short setup* decision.
4. **Keep trusting any desktop** and drop DEBT-0020 with that reason
   stated in the README: debug builds only (ADR-0072), the developer's
   own LAN assumed.

Recommended: 1. It closes both cases for every device that met its
desktop once, at no setup step and no public API, with code that stays
small (one proto field, one TXT key, one file per install). Option 2's
proof is only worth its cost once the transport is TLS.

Questions for the owner, with the recommendation in each:

- **A. The trade-off of 1**: a developer who moves to another desktop
  clears the app's data, reinstalls, or sets `host`. Recommended: accept,
  documented in the README and SDD 04.
- **B. Several desktops of one developer** (macOS, Linux, Windows):
  allow the desktop to take another desktop's id (Settings, "Use the id
  of another desktop", pasted)? Recommended: not now, a DEBT whose trigger
  is a developer pairing one device with two desktops.
- **C. The headless server** (`make server`, end to ends): no id by
  default, `--desktop-id ID` to test the pin. Recommended: yes; the end
  to ends use a manual host and stay as they are.

## Decision

Option 1, decided by the owner on 2026-10-31, with A, B and C as
recommended:

- **A.** A developer who moves a device to another desktop clears the
  app's data, reinstalls, or sets `host`; the README and SDD 04 say so.
- **B.** No shared id between one developer's desktops for now: a DEBT
  whose trigger is a developer pairing one device with two desktops.
- **C.** The headless server sends no id by default; `--desktop-id ID`
  sends one, to test the pin. The end to ends keep their manual host.

## Consequences

If 1 is accepted:

- `core.proto` gains `HelloAck.desktop_id` (field 4, optional: an SDK
  without it still connects); fixtures regenerated and mirrored in
  `Samples.swift`; TXT key `id`; `DesktopSelector` takes the pinned id.
- `sdk/core` and `sdk/ios` write and read the pin file beside the device
  id; SDD 04 and SDD 06 describe the check.
- Tests: `./gradlew :sdk:core:allTests` and `swift test` with two stand-in
  desktops, the second refused once the first approved; `make e2e-android`
  and `make e2e-ios` unchanged (manual host).
- DEBT-0020 is paid for the cases above; the first connection and a
  copied id on the desktop's LAN stay open, tied to the plain transport.
