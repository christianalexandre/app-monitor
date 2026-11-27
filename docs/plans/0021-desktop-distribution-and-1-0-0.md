---
id: PLAN-0021
title: Desktop distribution and 1.0.0
status: Done # Draft | Active | Done | Abandoned
milestone: M11
started: 2026-11-26
finished: 2026-11-27
---

# PLAN-0021 — Desktop distribution and 1.0.0 (M11)

## Goal

A developer installs the desktop with one command on macOS (Homebrew) and Windows (Scoop),
or from a package on Linux. The desktop tells them when a newer version is out. The libraries
and the desktop ship as `1.0.0` at one tag.

Owner decisions this plan follows:

- **Straight to `1.0.0`, libraries and desktop at one tag** (2026-11-25). There is no `0.x`
  desktop: `jpackage` refuses a macOS `app-version` starting with 0, and the libraries are
  unchanged since `0.1.0`.
- **DEBT-0135 before the release** (2026-11-25). Central's grace period ends 2026-12-14.
- **An update notice, no self-installing update** (2026-11-25). The desktop reads its GitHub
  Releases and shows a newer version with its link, and a Settings switch turns the check off.
- **One repository, `christianalexandre/homebrew-tap`, for Homebrew and Scoop** (2026-11-26).
  It holds the Homebrew cask and the Scoop bucket's manifest. Linux gets `.deb` and `.rpm` from
  the GitHub Release. The old app's tap repository stays as it is.
- **Unsigned packages** (ADR-0055), with the steps to let them run in `docs/guide/`.

Owner setup this plan needs before M11.3: the public repository
`christianalexandre/homebrew-tap`, and a fine-grained token with *Contents: write* on that
repository only, kept as a secret of this one. M11.3 names the secret.

## Tasks

- [x] **M11.1 The Central bundle under the monthly file count.** (`build/central-bundle-under-file-count`) DEBT-0135:
  `scripts/central_upload.sh` drops the `.sha256` and `.sha512` files, which Central does not
  require, taking a release from 1,068 files to about 712. `scripts/test_central_upload.sh`
  checks the list.
  Accept: `make scripts-test` and `make publish-dry-run` with the file count printed; DEBT-0135
  paid.
- [x] **M11.2 The desktop's packages on every release.** (`feat/desktop-packages-on-release`) The desktop's `packageVersion`
  comes from `VERSION_NAME` without its suffix, and `dev` moves to `1.0.0-SNAPSHOT` (a `0.x`
  cannot be packaged on macOS). `scripts/version_check.sh` covers the desktop too. The publish
  workflow builds the `.dmg` and the zipped `.app` on macOS, the `.msi` and a zipped app on
  Windows, and the `.deb` and `.rpm` on Linux. It attaches them to the tag's GitHub Release, and
  is dry on pull requests that change it. `docs/guide/desktop.md` gets the install and
  first-run steps per OS, for unsigned packages.
  Accept: `make scripts-test`; the workflow's dry run on its PR listing each package; one
  package built locally per OS at hand, opened.
  Done without a change to `scripts/version_check.sh`: the version comes from `VERSION_NAME`
  itself, and jpackage refuses a `0.x` on macOS (ADR-0227).
- [x] **M11.3 Homebrew and Scoop from `homebrew-tap`.** (`feat/homebrew-and-scoop`) The publish workflow writes
  `Casks/app-monitor.rb` and `bucket/app-monitor.json` (version, URLs, SHA-256) to
  `christianalexandre/homebrew-tap` once the release has its packages. The guide gives
  `brew install --cask christianalexandre/tap/app-monitor` and the `scoop bucket add` /
  `scoop install` lines.
  Accept: `brew style` and `brew audit --cask` on the generated cask; the Scoop manifest checked
  against Scoop's schema; a script test for both generators. Without the repository or the
  token: stop and ask the owner.
  Done after M11.4, once the owner had the repository and the token. `brew style`, `brew
  audit --cask --strict`, and an install from a local tap with the `.dmg` built here, all
  passed. The Scoop schema check was not run: fetching and running its validator from npm was
  refused in the session, and Scoop needs Windows. M11.5 checks both on the owner's machine.
- [x] **M11.4 A newer version, noticed.** (`feat/update-notice`) The desktop reads the repository's latest GitHub
  Release at launch and once a day. A newer version shows in the status bar with its link,
  and a Settings switch, on by default, turns the check off. No telemetry: one unauthenticated
  `GET`, nothing about the machine sent. An ADR records the desktop's first call outside the
  local network, `Accepted` on the owner's decision of 2026-11-25 (ADR-0094).
  Accept: `./gradlew :desktop:app:test` (and the feature or data module tests that own it),
  against a stand-in releases endpoint: newer, same, offline, switched off; a screenshot of the
  notice.
  Done before M11.3, which waits for the owner's `homebrew-tap` repository and token. The
  status bar's links became `StatusLink`: a `TextButton` there was cut by the bar's height.
- [x] **M11.5 `1.0.0`.** (`release/1.0.0`, then `release/desktop-1.1.0`) `VERSION_NAME`, the SDKs' version constants and the docs move to
  `1.0.0`, and the release PR `dev` → `main` is opened. The owner merges it and pushes the tag;
  the publish workflow does the rest. `scripts/consumers_check.sh 1.0.0` confirms what an app
  resolves, and `brew install` / `scoop install` confirm the desktop.
  Accept: the release gate green on the PR; after the owner's tag, `consumers_check.sh 1.0.0`
  green and the packages installed from the tap and the bucket.
  Done in two releases. `1.0.0` (#440) published the SDKs, but its packages failed at launch on
  every OS (ADR-0230, #442). The desktop then got its own version (ADR-0232, #445) and shipped
  as `desktop-1.1.0` (#446), the SDKs left at `1.0.0`. `consumers_check.sh 1.0.0` green; the
  owner opened the Linux and Windows packages of #445's dry run; `brew upgrade --cask
  app-monitor` took `1.1.0`, whose `.dmg`, downloaded from the release, launched here. `scoop
  install` was not run: it needs Windows.
- [x] **M11.R1 A tag that fails the version check publishes nothing.** (`fix/release-needs-version-check`) The `release` job
  needs `version` and runs only when it succeeded; the `.dmg`'s architecture in one place and
  `desktop_packages.sh`' glob quoted; the scripts' header comments back under four lines. Accept: a tag that is not on `main`, or does not match its version, ends with
  no GitHub Release (the workflow's conditions read, `actionlint` if available).
- [x] **M11.R2 The update notice's click cannot crash the window.** (`fix/update-notice-click-cannot-crash`) Opening the release's
  page goes through `runCatching`; `GitHubReleases`' KDoc under four lines. Accept: a window
  test with a `UriHandler` that throws clicks the notice and the window stays.

## Outcome

### M11 milestone review

`code-review` at `medium` of `7050c53..d964eed` (#436 to #449): a static read of the diff, 62
files, no Gradle test run by it. Eight findings, each then read in the code here. Finding 3 is
a correctness finding left open (DEBT-0141); the owner merges this PR.

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | A tag the `version` job refuses still gets a GitHub Release: `central` and `desktop` are skipped, not failed, and `release` only refuses a `failure`. | Yes: a mistyped tag publishes a release every desktop then announces | M11.R1 |
| 2 | The update notice's click calls `openUri` unguarded; where the platform cannot open a browser it throws inside the click. | Rarely: Linux without `xdg-open`; a crash, and one line | M11.R2 |
| 3 | `UpdateChecker`'s stored switch, read after start, overwrites a `setChecking` made meanwhile; a store read that throws ends the loop. | No: needs a toggle in the first moments, or a failing store | DEBT-0141 |
| 4 | "Clear all data" re-stores the switch through `setChecking`, which also wakes a check. | No: one extra request per clear | DEBT-0141 |
| 5 | `tap_manifests.sh` names the `.dmg` `macos-arm64` while `desktop_packages.sh` takes `uname -m`. | No: `macos-latest` is Apple silicon; its file is in M11.R1 | M11.R1 |
| 6 | `desktop_packages.sh`' `one()` splits a glob on a space in the checkout path. | No: CI's paths have none; its file is in M11.R1 | M11.R1 |
| 7 | `GitHubReleases`' KDoc and the headers of `central_upload.sh`, `tap_manifests.sh`, `desktop_packages.sh` over four lines. | A written rule (guideline 02) | M11.R1, M11.R2 |
| 8 | `central_upload.sh` cites DEBT-0135 as paid: history in source. | A written rule (guideline 02) | M11.R1 |

- **Shipped**: M11.1 the Central bundle under the monthly file count (#436). M11.2 the
  desktop's packages on every release (#437). M11.4 the update notice (#438). M11.3 the
  Homebrew cask and Scoop manifest (#439). M11.5 `1.0.0` (#440, back-merge #441), then the
  runtime image fix (#442), the cask's URL check left to Homebrew (#443), Central's wait ending
  at `PUBLISHING` (#444), the desktop's own version (#445), `desktop-1.1.0` (#446, back-merge
  #448, snapshot #449) and the plugin's TestKit stand-ins kept off Maven Central (#447).
  M11.R1 a refused tag publishes nothing (#450). M11.R2 the update notice's click guarded,
  and the close (this PR). Plan PR #435.
- **Decisions**: ADR-0227 to ADR-0232.
- **Debt**: Paid DEBT-0135 (M11.1). Created DEBT-0139 (packages never launched, re-triggered
  to `release/desktop-1.2.0`), DEBT-0140 (`MonitorPauseTest` failed once in the gate) and
  DEBT-0141 (this review).
- **Deviations**: M11.4 before M11.3, which waited for the owner's tap repository and token.
  M11.5 grew: `1.0.0`'s packages did not start, so the desktop split from the SDKs' version
  (ADR-0232) and shipped again as `desktop-1.1.0`, the owner's pick over `1.0.1`. Two gate
  failures on the way: #447's TestKit stand-ins, shadowed by the real `1.0.0` once on Central,
  and DEBT-0140.
- **Lessons**: nothing launched the packages before `1.0.0` (DEBT-0139); a release should be
  opened from its own package before the tag. A test that resolves this project's coordinates
  must pin where they come from, since a release puts them on Maven Central.
