---
id: ADR-0132
title: Cleartext to the desktop is allowed by a debug source set config, not debug-overrides
status: Superseded
date: 2026-10-25
superseded_by: ADR-0156
---

# ADR-0132 — Cleartext to the desktop is allowed by a debug source set config, not debug-overrides

## Context

ADR-0017 F decided that the SDK ships no network security config and that
the app follows "a documented `debug-overrides` snippet", named again by
the transport's log line when the policy blocks `ws://` (ADR-0023). Not
reopened here: no config shipped, one log line, then stopped.

`<debug-overrides>` cannot allow cleartext. Android's reference lists
`<trust-anchors>` as its only child and no attributes;
`cleartextTrafficPermitted` is an attribute of `<base-config>` and
`<domain-config>` only
(developer.android.com/privacy-and-security/security-config, read
2026-10-25). Both samples already do the other half of ADR-0017 F's option
2: `src/debug/res/xml/network_security_config.xml` with
`<base-config cleartextTrafficPermitted="true">`, referenced from
`src/debug/AndroidManifest.xml`. The H1 review (PLAN-0011) found the
README showed neither, and the log line sent developers to a fix that does
not exist in a README that does not exist.

## Options

1. **Debug source set config, as the samples** — a manifest and an XML in
   `src/debug/`, so release builds keep the app's policy. Works on every
   API level the SDK supports.
2. **`<domain-config>` for the desktop's address** — narrower, but the
   desktop's LAN address is found at runtime by mDNS and changes with the
   network; a fixed domain list does not hold it.

## Decision

1. The README shows the samples' debug source set config; the log line
   names `cleartextTrafficPermitted` and the README's Usage section. This
   corrects the snippet ADR-0017 F named, not its decision: no config
   shipped, a documented fix, an error naming it.

## Consequences

- A developer following the README gets a debug build that reaches the
  desktop on targetSdk ≥ 28.
- An app with its own network security config makes a debug copy of it
  with the attribute added; the README says so.
