---
id: ADR-0018
title: What the Android SDK captures and masks, and device approval
status: Proposed
date: 2026-10-22
superseded_by:
---

# ADR-0018 — What the Android SDK captures and masks, and device approval

## Context

The SDK copies an app's HTTP traffic — tokens, cookies, personal data — to
another machine over plain `ws://` on the LAN. What is captured, masked,
sent and remembered is a "stop and ask" area (`AGENTS.md`, security and
privacy); ADR-0002 already requires masking and device approval before any
public release. The public API that carries these options is ADR-0017.

Already decided, not reopened here:

- The device is the client; it finds the desktop by manual host, mDNS or
  `10.0.2.2`. Nothing authenticates either end (ADR-0002, ADR-0009).
- `Hello` carries `device_id` ("stable per install, chosen by the SDK"),
  `device_name`, `os_version`, `app_id`, `app_version`
  (`protocol/proto/core.proto`). The desktop answers `HelloAck`; without
  `Hello` in 10 s it closes with 1002 (SDD 01).
- Bodies get `body_truncated` and the original size in M1.2; the desktop's
  `maxFrameSize` derives from the body limit in M1.3 (PLAN-0002).
- Persistence (devices, apps, runs) lands in M5 (roadmap).

Evidence, checked on 2026-10-22:

- OkHttp's `HttpLoggingInterceptor` masks headers by name, case-insensitive,
  with `redactHeader(name)`, and query parameters with
  `redactQueryParams(...)`, replacing the value with `██`; nothing is
  redacted by default (OkHttp `HttpLoggingInterceptor.kt`).
- `Response.peekBody(byteCount)` "loads the requested bytes into memory.
  Most applications should set a modest limit on `byteCount`, such as
  1 MiB" (OkHttp `Response.kt`). Request bodies may be one-shot or duplex
  and then cannot be read twice (`RequestBody.isOneShot()`, `isDuplex()`).
- Anything on the LAN can advertise `_appmonitor._tcp`: mDNS "assumes
  cooperating participants" and needs IPsec or DNSSEC signatures to tell
  trusted ones apart (RFC 6762 §21); the desktop uses neither (ADR-0009).

## Options

### A. Where masking runs

1. **On the device, before the wire** — the secret never leaves the app;
   the desktop cannot unmask it.
2. **On the desktop, at ingest or display** — one implementation for every
   SDK and a "reveal" button; but the secret crosses the LAN in clear text
   and reaches whatever desktop the device connected to.

Recommended: 1 (the plan's premise). Every SDK implements the same rule,
pinned by a shared test vector.

### B. What is masked by default

1. **Headers `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`**
   (case-insensitive), value replaced by `██`, the header name kept so the
   developer sees it was sent. Options add names (`maskedHeaders`) and query
   parameter names (`maskedQueryParameters`, none by default). Bodies are
   never masked in M1.
2. **(1) plus common token headers** (`X-Api-Key`, `X-Auth-Token`…) — wider,
   but a guessed list that still misses each app's own header.
3. **Nothing by default**, like `HttpLoggingInterceptor` — the developer opts
   in; the default leaks every bearer token.

Recommended: 1. Body masking (JSON field names, form fields) becomes a
DEBT with the trigger "first request to mask a body field".

### C. Body capture limit

1. **1 MiB per body, configurable (`maxBodyBytes`)**, request and response
   alike; the first N bytes are sent with `body_truncated` and the original
   size when known (M1.2). OkHttp's own suggested bound.
2. **256 KiB** — smaller frames and memory, but cuts ordinary JSON lists.
3. **No body by default** — safest, loses most of the tool's value.

Bodies the SDK cannot read without changing the app's call — one-shot or
duplex request bodies, streams the app never reads — are sent empty with a
marker, never consumed. Response bodies are copied as the app reads them
(not read ahead), so a streaming response is not delayed.

Recommended: 1.

### D. Device identity

1. **Random UUID generated on first `start()`, stored in the app's
   no-backup storage** — stable per install, reset on reinstall, links
   nothing across apps.
2. **`Settings.Secure.ANDROID_ID`** — stable across reinstalls, but an
   identifier Android restricts for privacy.

Recommended: 1. `device_name` is `Build.MANUFACTURER` + `Build.MODEL`, not
the user-set device name.

### E. Device approval

1. **Approval per (`device_id`, `app_id`) before `HelloAck`.** The desktop
   reads `Hello`, shows "<device_name> — <app_id> wants to connect", and
   keeps the socket open without a timeout while it waits. Approve → the
   server sends `HelloAck`; reject → close with 1008 (policy violation).
   The SDK holds events in its ring buffer meanwhile (M1.4). Approved and
   rejected pairs are remembered in memory until the desktop app quits;
   persisted in M5. A rejected pair is closed at once on reconnect; the
   SDK backs off.
2. **Approval per device only** — fewer dialogs; one approval admits every
   app on that device.
3. **No approval for the emulator** (`10.0.2.2` / loopback peers) — fewer
   clicks in the common case; the peer address is the only proof.

Recommended: 1. Option 3 can follow as a desktop setting if the dialog
annoys.

### F. Which desktop the device trusts

The approval protects the desktop, not the device: a device sends its
(masked) traffic to the first `_appmonitor._tcp` it finds.

1. **Accept for M1, document it** — debug builds only, same LAN, secrets
   masked by default; a DEBT whose trigger is "first public release of an
   SDK" (pairing code or desktop fingerprint pinned on the device).
2. **Pair now** — the desktop shows a code, the developer enters it once in
   the app; costs UI on both ends in M1.

Recommended: 1.

## Decision

Empty while `Proposed`. The owner chooses A–F (or accepts the
recommendations).

## Consequences

- `AppMonitorOptions` (ADR-0017) gains `maskedHeaders`,
  `maskedQueryParameters` and `maxBodyBytes`.
- The desktop server gets a pending state between `Hello` and `HelloAck`;
  the SDD lifecycle in M1.3/M1.4/M1.9 documents it.
- The iOS SDK (M2) applies the same defaults and limit.
