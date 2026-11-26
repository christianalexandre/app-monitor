---
id: PLAN-0021
title: Desktop distribution and 1.0.0
status: Active # Draft | Active | Done | Abandoned
milestone: M11
started: 2026-11-26
finished:
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
- [ ] **M11.5 `1.0.0`.** `VERSION_NAME`, the SDKs' version constants and the docs move to
  `1.0.0`, and the release PR `dev` → `main` is opened. The owner merges it and pushes the tag;
  the publish workflow does the rest. `scripts/consumers_check.sh 1.0.0` confirms what an app
  resolves, and `brew install` / `scoop install` confirm the desktop.
  Accept: the release gate green on the PR; after the owner's tag, `consumers_check.sh 1.0.0`
  green and the packages installed from the tap and the bucket.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
