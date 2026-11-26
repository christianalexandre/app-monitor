---
id: ADR-0232
title: The desktop has its own version and tag, on the SDKs' major
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0232 — The desktop has its own version and tag, on the SDKs' major

## Context

`1.0.0` released the SDKs and the desktop together: one `VERSION_NAME`, one `X.Y.Z` tag,
one publish run (ADR-0216, ADR-0227). Its desktop packages did not start (ADR-0230), and
the fix touches only the desktop. With one version, shipping it means libraries `1.0.1`
that are `1.0.0`'s code, 712 more files on Maven Central in a month already over the free
count (DEBT-0135), and a new SwiftPM tag nobody needs.

The owner decided on 2026-11-26 that the libraries and the desktop get separate releases,
and that only a major version (a breaking change, `2.0.0`) moves both.

## Options

1. **A second version, `DESKTOP_VERSION_NAME`, released at `desktop-X.Y.Z` tags.** SwiftPM
   reads only semantic version tags, so a `desktop-` tag is invisible to it. The publish
   workflow picks its jobs by the tag's kind.
2. **One version, and a switch that skips Maven Central.** The SDKs' `Hello` and SwiftPM
   would still move to the new version with no change in them.
3. **The desktop in its own repository.** Its own tags and releases, but the protocol, the
   fixtures and the end to ends live here.

## Decision

Option 1, as the owner decided:

- `gradle.properties` holds `DESKTOP_VERSION_NAME`: the packages' version, the
  `-Dappmonitor.version` the app and its MCP server report. `scripts/version_check.sh`
  fails when its major differs from `VERSION_NAME`'s, and `--release desktop-X.Y.Z`
  checks the tag against it.
- `publish.yml`: a `version` job checks either tag. An `X.Y.Z` tag runs `central` and a
  GitHub Release without packages; a `desktop-X.Y.Z` tag runs `desktop`, a release titled
  *Desktop X.Y.Z* with the packages, then `tap`. A pull request runs both dry.
- The cask and the Scoop manifest download from `desktop-X.Y.Z` and look for updates in
  `desktop-` tags only.
- The update notice (ADR-0228) reads the release list and takes the first published
  `desktop-` release, since `releases/latest` can be an SDK release.
- `CHANGELOG.md` heads desktop sections `## Desktop X.Y.Z`; release branches are
  `release/desktop-X.Y.Z`.

## Consequences

- A desktop fix ships without a library release, and the reverse.
- "Which desktop for which SDK" is the major version; the compatibility guide says so.
- The first desktop release under this scheme is `desktop-1.0.1`; `1.0.0`'s release keeps
  the SDKs only, its packages removed (ADR-0230).
- A `1.0.0` desktop, which did not start, never saw the old update check; nothing reads
  `releases/latest` any more.
- `dev` holds `DESKTOP_VERSION_NAME=X.Y.Z-SNAPSHOT` like the SDKs' version.
