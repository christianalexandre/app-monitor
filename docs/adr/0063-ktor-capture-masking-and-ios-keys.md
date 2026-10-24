---
id: ADR-0063
title: What the Ktor plugin captures and masks, and the keys a KMP iOS app needs
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0063 — What the Ktor plugin captures and masks, and the keys a KMP iOS app needs

## Context

PLAN-0005 M4.1 (b). `sdk/ktor` copies a KMP app's Ktor traffic to the
desktop over plain `ws://`; what it captures, masks and asks of the app is a
"stop and ask" area (`AGENTS.md`, security and privacy). Its public API is
ADR-0062.

Already decided, not reopened here:

- Masking runs on the device, before the wire; every SDK applies the same
  rule, pinned by `protocol/fixtures/masking.json`: `Authorization`,
  `Proxy-Authorization`, `Cookie`, `Set-Cookie` plus `maskedHeaders` →
  `██`, name kept; `maskedQueryParameters` in the query and a
  `name=value&…` fragment; URL user info; `Location` and
  `Content-Location` masked as URLs; bodies never masked (ADR-0018,
  ADR-0032, ADR-0033, ADR-0044, DEBT-0019).
- `masking = false` turns every rule off; the default is `true`
  (ADR-0034, ADR-0036 B).
- 1 MiB per body by default; unreadable bodies sent empty with
  `body_truncated`; WebSocket frames cut the same way (ADR-0018 C,
  ADR-0020, ADR-0028). Adapters send capture layer `ADAPTER` (ADR-0038).
- Swift apps get the plist keys from `add_local_network_keys.sh` in a Run
  Script phase, for configurations whose name contains `Debug` (ADR-0049).

Evidence, Ktor 3.6.0 sources (`ktorio/ktor` tag `3.6.0`), read on
2026-10-24:

- Ktor's `Logging` masks headers only, by predicate
  (`sanitizeHeader(placeholder = "***") { … }`), nothing by default
  (`ktor-client-logging/.../Logging.kt:113`).
- Plugins add headers at different stages: `HttpCookies` adds `Cookie` in
  `sendPipeline` at `HttpSendPipeline.State`
  (`plugins/cookies/HttpCookies.kt:131`); the public `SendingRequest` hook
  runs in the same phase, "for every request, even if it's not user
  initiated" such as redirects (`plugins/api/CommonHooks.kt:65`). What a
  capture sees therefore depends on its hook and install order; headers an
  engine adds itself (Darwin's `NSURLSession`) are never visible to a
  plugin.
- WebSocket frames: `WebSocketExtension` sees every frame both ways
  (`processOutgoingFrame`, `processIncomingFrame`,
  `ktor-websockets/.../WebSocketExtension.kt:118`), but extensions run only
  on engines declaring `WebSocketExtensionsCapability` — CIO and
  `MockEngine`, not Darwin or OkHttp (`grep` over `ktor-client`). Frame
  capture on Darwin needs another route (a wrapped session), M4.6's
  choice.
- The KMP iOS app's `Info.plist` is the app's own Xcode target; the Kotlin
  framework adds no keys (Kotlin direct integration docs, ADR-0062).

## Options

### A. Where a secret can sit, and what happens to it

| Place in a Ktor exchange | Rule (option A.1) |
| --- | --- |
| Request headers, also from `DefaultRequest`, `Auth` (bearer), `HttpCookies` | Header rule, captured after those plugins ran; M4.5's test proves `Cookie` and `Authorization` are seen |
| Headers the engine adds (Darwin, OkHttp) | Not seen |
| URL user info, query, fragment | URL rule |
| Response headers, `Set-Cookie`, `Location`, `Content-Location` | Header and URL rules |
| Redirect hops followed by `HttpRedirect` | One exchange per hop, each masked |
| WebSocket handshake headers | Header rule |
| WebSocket frames | Not masked, cut at `maxBodyBytes` (in scope, M4.6) |
| Request and response bodies, multipart, streamed `ChannelWriterContent` | Not masked; a stream the plugin cannot read without consuming is sent empty with `body_truncated` |
| `Auth` credentials providers, client certificates | Never captured |

1. **The shared rule, member for member** (above), pinned by the masking
   vector on JVM and the iOS simulator; `masking = false` turns it off;
   default `true`. WebSocket frames in scope.
2. **Option 1 without WebSocket frames** — M4.6 dropped; Ktor WebSocket
   apps then show the handshake only.
3. **Ktor-only extras** (bearer tokens in frames) — the SDKs would differ,
   against ADR-0036 A.

Recommended: 1.

### B. Local network keys for a KMP iOS app

1. **Reuse `add_local_network_keys.sh` (ADR-0049) in the KMP app's Xcode
   target**, same `Debug` rule, so the keys follow the SDK into the builds
   that contain it (ADR-0062 D). The KMP sample calls it by relative path;
   how an app outside the repository gets the script is M10, as for Swift.
2. **The app adds the keys by hand** — they ship in release and show a
   permission string for a tool the release does not contain (rejected by
   ADR-0036 E).

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- M4.5 adds the masking vector test on JVM and `iosSimulatorArm64`.
- With A.1, M4.6 must find a frame route on Darwin and OkHttp engines, or
  state the engines it covers in its own ADR.
- Local network privacy is only provable on a device (TN3179, ADR-0036);
  M4 has no device step, so it stays unproven for KMP until DEBT-0041's
  trigger.
