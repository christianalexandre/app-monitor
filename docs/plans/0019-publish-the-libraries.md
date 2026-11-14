---
id: PLAN-0019
title: Publish the libraries — Maven Central and SwiftPM, one version
status: Active # Draft | Active | Done | Abandoned
milestone: M10
started: 2026-11-13
finished:
---

# PLAN-0019 — Publish the libraries (M10)

## Goal

One `X.Y.Z` tag on `main` publishes every SDK at that version: the Kotlin
artifacts and their no-ops on Maven Central, the Gradle plugin with them, and
the root `Package.swift` for SwiftPM by the tag itself. An Android, iOS or
KMP app installs it with the README's steps and no `mavenLocal` or branch
rule; `0.1.0` is the first one the owner tests in a real app.

The owner lifted the `Hold` on 2026-11-13 and split the old M10 in two:
the libraries here, the desktop app's packages in M11, after the owner has
tested `0.1.0` in a project (the `Hold` before M11 in the roadmap).

Owner decisions this plan follows (2026-11-13):

- **One version for both platforms.** Android, KMP and iOS ship the same
  number at the same tag, `0.1.0` first, `1.0.0` when the API is frozen
  (ADR-0054: one `X.Y.Z` tag for every SDK).
- **The Gradle plugin on Maven Central** with the SDK, same tag (ADR-0093
  E.1). An Android Studio project's `pluginManagement` already lists
  `mavenCentral()`, so it adds no setup step; a project without it adds one
  line, which the README says.
- **The release is the owner's.** The loop prepares and verifies; the
  `dev` → `main` PR, its merge and the tag are the owner's
  (`docs/guidelines/03_git_and_pull_requests.md`).

## Before the loop needs them (owner)

Tasks that upload stop at `[!]` until these exist; the rest does not need
them:

- [x] A Central Portal account (central.sonatype.com) with the namespace
  `io.github.christianalexandre` verified, and a user token (2026-11-14).
- [x] A GPG key for signing, its public part on `keyserver.ubuntu.com` and
  `keys.openpgp.org` (2026-11-14).
- [x] Repository secrets `APPMONITOR_SIGNING_KEY`,
  `APPMONITOR_SIGNING_PASSWORD`, `MAVEN_CENTRAL_USERNAME`,
  `MAVEN_CENTRAL_PASSWORD` (2026-11-14; SDD 02 *Publishing a release*).

## Tasks

- [x] **M10.1 Artifacts Central accepts.** (`build/sign-for-central`) Every publication the dry-run
  makes (ADR-0058: protocol, core, okhttp, ktor, the three no-ops, each
  target) plus the Gradle plugin and its marker get a signature, a sources
  jar and a javadoc jar, and the POM Central requires. The upload path
  (Gradle's `maven-publish` and the Portal's bundle API, or a publishing
  plugin) is an ADR; ADR-0058 left it to M10. Signing reads the key from
  the environment, never from a file in the repository.
  Accept: `make publish-dry-run` signs with a throwaway key made by the
  script, checks every file's `.asc`, the javadoc and sources jars, the
  plugin marker's POM, and fails on a missing one; `make check` green.
- [x] **M10.2 One version, checked.** (`build/one-version-checked`) A script reads the version from
  `gradle.properties` and fails unless the tag, the plugin's SDK version
  (ADR-0157) and the version each SDK sends in `Hello` (`SDK_VERSION` in
  `sdk/core`, `sdkVersion` in `sdk/ios`, both `0.1.0-dev` today) agree; a `-SNAPSHOT` or `-dev`
  version never publishes. `dev` keeps `X.Y.Z-SNAPSHOT`; the release PR
  drops the suffix. Accept: the script's bash test in `make scripts-test`
  (a matching tag, a mismatching one, a snapshot refused).
- [x] **M10.3 The publish workflow.** (`ci/publish-workflow`) `.github/workflows/publish.yml` runs
  on a pushed `X.Y.Z` tag that is on `main`: M10.2's check, then the
  upload on macOS (the iOS klibs need it, ADR-0058), then a GitHub Release
  with the notes of that version. SwiftPM needs nothing beyond the tag. A
  `workflow_dispatch` dry-run input publishes to the dry-run repository
  without secrets. The secrets' names are in SDD 02 and the owner's setup
  steps in the PR. Accept: the dry-run dispatched on the branch, green;
  `scripts/ruleset_check.sh` green.
- [x] **M10.4 Consumers of the published coordinates.** (`test/published-consumers`) A script, given a
  version, builds a throwaway Android app that applies only the plugin from
  `mavenCentral()` (debug with the SDK, release without, as the TestKit
  consumer of ADR-0126), a KMP one on `appmonitor-ktor` with an
  `iosSimulatorArm64` target on Kotlin 2.2 (DEBT-0117), and a SwiftPM one
  resolving the repository's URL at the tag, in debug and release. Before
  `0.1.0` exists it runs against `mavenLocal` and a local tag, so it is
  proven before the upload. Accept: the script green against
  `mavenLocal` and a local tag; its command in AGENTS.md.
- [ ] **M10.5 Installation from the published version.** The README's
  *Installation* drops *Until it is published*: the plugin line on
  Android, the coordinates on KMP, the package URL and an *Up to Next
  Minor Version* rule from `0.1.0` on iOS. ADR-0162 is superseded. SDD 02
  gets *Cutting a release*: version bump PR, release PR, the owner's merge,
  the tag, the workflow, and how to check the Central deployment. A
  `CHANGELOG.md` starts with `0.1.0`. Accept: `make docs-check` green; each
  README step matches what M10.4's consumers do.
- [ ] **M10.6 Release `0.1.0`.** The release PR `dev` → `main` with the
  version `0.1.0`, the release gate green on it; the owner merges and
  tags. The loop opens the PR and stops (owner-only merge); after the tag
  the owner runs M10.4's script against `0.1.0` and the field test in a
  real app. Accept: the workflow's run green, the Central artifacts and
  the SwiftPM tag resolving, M10.4's script green against `0.1.0`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
