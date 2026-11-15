---
id: ADR-0220
title: The README is for app developers, and docs/guide holds the details
status: Accepted
date: 2026-11-15
superseded_by:
---

# ADR-0220 — The README is for app developers, and docs/guide holds the details

## Context

`0.1.0` is on Maven Central and at a SwiftPM tag (PLAN-0019 M10.6), so
ADR-0162's *Until it is published* section, which installed from
`mavenLocal` and the `dev` branch, is wrong. The README had grown to about
480 lines:
- a pre-alpha status note, a roadmap and a platform table;
- every installation detail, the full iOS Run Script included;
- the MCP setup for every client;
- how the project is built.

The owner asked for a front page that says what App Monitor is and how
to use it, with the rest in sub-pages (2026-11-15):
- debug only stated as app-network-monitor does;
- the roadmap off it;
- the platform table moved to a compatibility page;
- installation as a short guide per platform plus the desktop, with a call
  to the physical device setup.

Constraints:
- **Short setup** (standing decision): the steps stay copy-paste. The iOS
  Run Script stays inline somewhere a developer copies it from, and
  `scripts/test_add_local_network_keys.sh` checks that copy line for line
  (ADR-0155).
- **`docs/` holds what the project is.** Each of its five folders has its
  own nature.

## Options

1. **A short README plus `docs/guide/`**, one page per platform, the desktop,
   MCP and compatibility. Every detail of the old README moves there; the
   README links to it.
2. **Everything in the README, folded in `<details>` blocks.** One page, but
   still long, and GitHub's search and anchors do not open folded blocks.
3. **A docs site.** It is M12's scope (the roadmap's *Front page*), not this
   task's.

## Decision

Option 1.
- **The README:** what App Monitor is, the debug-only note, why, how it
  works, getting started, a line on MCP, links, license.
- **Getting started:**
  1. the desktop;
  2. the SDK on Android, iOS and KMP;
  3. running the debug build, with a callout for physical devices.
- **`docs/guide/`** holds a page each for Android, iOS, KMP, the desktop,
  MCP and compatibility. The Run Script lives in `docs/guide/ios.md`, and
  its check reads that file.
- **How the project is built** moves to `docs/README.md`.
- **The roadmap** lives only in `docs/plans/README.md`.
- **ADR-0162 is superseded**, except for its SDK logs of missing setup,
  which stay as they are. Testing an unreleased SDK from `mavenLocal` moves
  to SDD 02.

## Consequences

- A change to what an app developer does updates `docs/guide/`, in the same
  PR, as a design change updates `docs/sdd/`.
- Some messages and docs point at the README, such as the iOS SDK's
  "README's Run Script (Installation, iOS)". They stay valid: the README's
  step 4 links to the script.
- The README's install steps match what `scripts/consumers_check.sh` builds
  (ADR-0218): the plugin line, the package URL, *Up to Next Minor* from
  `0.1.0`.
