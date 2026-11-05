---
id: ADR-0162
title: Until M10 the README installs from Maven Local and the dev branch, and the SDK says what setup is missing
status: Accepted
date: 2026-10-30
superseded_by:
---

# ADR-0162 — Until M10 the README installs from Maven Local and the dev branch, and the SDK says what setup is missing

## Context

H4.5 asks that a team installs App Monitor from the README alone, before
anything is published (M10). The first field test (2026-10-29) reached the
unpublished SDK by hand: `./gradlew publishToMavenLocal` for the SDK, an
init script applying `maven-publish` to `gradle-plugin` for the plugin,
because an `includeBuild` of `gradle-plugin` clashed with the app's own
`build-logic` included build, and the Swift package on branch `dev`.

When setup was missing, the SDK said little. On iOS a device without the
Run Script's keys only showed a failing `NWBrowser`, and a denied local
network permission printed the same line, pointing at `Info.plist`. On
Android 17 the client logged `mDNS skipped … request it` at its first
discovery, before the SDK's own dialog (ADR-0156) was even answered, and
nothing after a denial.

Two fresh projects written from the README alone (H4.5) found one more gap.
An app on AGP 9.3.3's built-in Kotlin, 2.2.0, does not compile against the
SDK: `Class 'appmonitor.sdk.core.AppMonitor' was compiled with an
incompatible version of Kotlin. The actual metadata version is 2.4.0, but
the compiler version 2.2.0 can read versions up to 2.3.0`. The SDK is built
with Kotlin 2.4.21 and uses `kotlin.uuid.Uuid`, stable since 2.4
(ADR-0066).

## Options

**A. How Android and KMP apps reach the unpublished plugin**

1. **`maven-publish` on `gradle-plugin`, `publishToMavenLocal`** — one
   command next to the SDK's, `mavenLocal` filtered to the group in the
   app's settings. The plugin's marker resolves by its id.
2. **`includeBuild` of the clone** — no publish step, but it clashed
   with the field-test app's own included build.
3. **An init script** — what the field test did; one more file to copy.
4. **JitPack** — builds from GitHub, but adds an outside service and
   its coordinates, which change again at M10.

**B. Which Swift package reference**

1. **Branch `dev`** — the default branch, whose README a visitor reads.
2. **Branch `main`** — released, but behind the README shown.

**C. Saying what is missing**

1. **One log line per missing piece, once** — iOS reads its `Info.plist`
   at start on a device; a `PolicyDenied` browser says *Settings*;
   Android logs a denial on the first Activity resumed after its dialog.
2. **Fail `start`** — loud, but a debug build that cannot reach a
   desktop must still run the app.

**D. Apps on Kotlin older than 2.3**

1. **State the floor in the README** — Kotlin 2.3 or newer, and the
   line that moves AGP 9's built-in Kotlin.
2. **Lower the SDK's language and API version** — loses `Uuid` as
   stable API, and the SDK's own dependencies are built with recent
   Kotlin too; not measured.

## Decision

A.1, B.1, C.1 and D.1. `gradle-plugin` applies `maven-publish` with the
SDK's group and version and the artifact `appmonitor-gradle-plugin`. The
README's *Until it is published* section lists both publish commands, the
`mavenLocal` blocks and the Swift branch `dev`. The iOS SDK logs missing
`NSLocalNetworkUsageDescription` or `_appmonitor._tcp` once per client on
a device, and a denied local network with the *Settings* path. Android
logs `ACCESS_LOCAL_NETWORK denied` once, when the Activity comes back from
the dialog without it. The README states Kotlin 2.3 as the floor; lowering
it is DEBT-0108.

## Consequences

- Before M10 the setup is the README's two steps on Android, four on iOS,
  plus the pre-M10 section: two publish commands and one settings block,
  or one dependency rule in Xcode.
- The plugin's local coordinates are the ones M10 publishes, so the
  settings block only loses `mavenLocal` then.
- `sdk/core` on iOS, used only by a KMP app on a non-Darwin Ktor engine
  (ADR-0158), keeps its connect-failure hint and gets no `Info.plist`
  check.
- An app on Kotlin 2.2 needs one more line; DEBT-0108.
