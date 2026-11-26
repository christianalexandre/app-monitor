---
id: ADR-0228
title: The desktop reads its latest GitHub Release and shows a newer version
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0228 — The desktop reads its latest GitHub Release and shows a newer version

## Context

PLAN-0021 M11.4. With packages published (ADR-0227), a developer who installed `1.0.0` has
no way to learn that `1.1.0` is out. The owner decided on 2026-11-25: an update notice in
`1.0.0`, not a self-installing update, with a Settings switch to turn it off.

This is the desktop's first call outside the local network. Until now it spoke only to
devices on the LAN, to MCP clients and the proxy on loopback (ADR-0090), and to nothing on
the internet. What it sends and to whom is a security / privacy choice. The owner made it
(2026-11-25), so this ADR is `Accepted` on that decision (ADR-0094).

Known constraints:

- GitHub's REST API serves `GET /repos/{owner}/{repo}/releases/latest` without
  authentication, up to 60 requests an hour per IP, and requires a `User-Agent`. The answer
  holds `tag_name` and `html_url`, and leaves out drafts and pre-releases
  ([REST API, releases](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)).
- `desktopVersion()` is `VERSION_NAME` (`1.0.0-SNAPSHOT` on `dev`), or `dev` from an IDE.

## Options

1. **Read the latest release at launch and every 24 hours, show a link in the status bar.**
   One unauthenticated `GET` to `api.github.com`. Its headers are only
   `User-Agent: AppMonitor/<version>` and `Accept`. Nothing about the machine, its devices or
   its traffic is sent. The switch is stored under `updates.check`, on unless `false`.
2. **Check only when the developer asks** (a "Check for updates" button). No call unless
   asked, but nobody finds out a new version exists, which is the point.
3. **Download and install the update.** The owner declined it. With unsigned packages it also
   means Gatekeeper, SmartScreen and root on Linux at every update.

## Decision

Option 1, as the owner decided:

- `UpdateChecker` (in `desktop/data`) checks at start and every 24 hours while the switch is
  on. A failed check keeps what the last one found. Turned off, it forgets what it found and
  asks nothing more. Turned on again, it checks at once.
- `GitHubReleases` (in `desktop/app`) is the call: 10-second timeouts, and `null` on any
  failure.
- A version is newer when its `X.Y.Z` numbers are greater. A suffixed running version
  (`1.0.0-SNAPSHOT`) comes before its release. A tag that is not numbers, or that has a
  suffix, is never newer.
- The status bar shows "Version X.Y.Z available" as a `StatusLink`, which opens the release
  page in the browser. *Settings → Updates* holds the switch, with a caption saying what is
  sent.

## Consequences

- GitHub sees the machine's IP address and the desktop's version, at launch and once a day,
  unless the switch is off.
- A developer offline, or behind a proxy that blocks GitHub, sees nothing and loses nothing:
  the call fails quietly.
- A build from the repository (`1.0.0-SNAPSHOT`) shows `1.0.0` as available once it is
  released. That is true.
- *Clear all data* deletes the stored switch. The window writes the current value back, so
  the choice survives, as the theme's does.
