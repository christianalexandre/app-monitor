---
id: ADR-0216
title: One version in three places, checked by a script; a release needs a matching X.Y.Z tag
status: Accepted
date: 2026-11-14
superseded_by:
---

# ADR-0216 — One version in three places, checked by a script; a release needs a matching X.Y.Z tag

## Context

PLAN-0019 M10.2. ADR-0054 gives every SDK one `X.Y.Z` version and tag, and
the owner confirmed it on 2026-11-13: Android, KMP and iOS ship the same
number. The version lives in three places today:

- `VERSION_NAME` in `gradle.properties`, which the Kotlin artifacts and the
  Gradle plugin's generated `SDK_VERSION` read (ADR-0058, ADR-0157);
- `SDK_VERSION` in `sdk/core`'s `Platform.kt`, sent in `Hello`;
- `sdkVersion` in `sdk/ios`'s `Device.swift`, sent in `Hello`.

The two `Hello` constants said `0.1.0-dev` while `VERSION_NAME` said
`0.1.0-SNAPSHOT`. SwiftPM builds from the tagged sources with no build
step, so the Swift constant cannot be generated at build time.

## Options

1. **Literals checked by a script** (chosen) — the three stay plain text;
   `scripts/version_check.sh` fails when they differ, and with `--release
   TAG` when the tag differs or the version has a suffix. Its bash test in
   `make scripts-test` also runs it on the repository, so `make check` keeps
   `dev` aligned.
2. **Generate the Kotlin constant from `VERSION_NAME`**, as the plugin does
   — one place fewer on the Kotlin side, but the Swift one is still a
   literal, so the script is needed anyway.
3. **Commit a generated Swift file** from `VERSION_NAME` — a generator and
   a freshness check for one string.

## Decision

Option 1. `dev` holds `X.Y.Z-SNAPSHOT` in all three. The version bump is a
commit on `release/x.y.z`, and the `dev` → `main` PR drops the suffix in all
three. M10.3's workflow runs `scripts/version_check.sh --release <tag>`
before any upload.

## Consequences

- A `Hello` from a `dev` build now says `0.1.0-SNAPSHOT` instead of
  `0.1.0-dev`. Nothing reads the value but the desktop's device row.
- A version bump touches three files; the script names the one left behind.
