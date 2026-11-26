---
id: PLAN-0019
title: Publish the libraries — Maven Central and SwiftPM, one version
status: Done # Draft | Active | Done | Abandoned
milestone: M10
started: 2026-11-13
finished: 2026-11-15
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
  `sdk/core`, `sdkVersion` in `sdk/ios`; `0.1.0-dev` before this task) agree; a `-SNAPSHOT` or `-dev`
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
- [x] **M10.6 Release `0.1.0`.** (`release/0.1.0`, #415) The release PR `dev` → `main` with the
  version `0.1.0`, the release gate green on it; `CHANGELOG.md`'s `0.1.0`
  section is the GitHub Release's notes. The owner merges and tags. The
  loop opens the PR and stops (owner-only merge); after the tag M10.4's
  script runs against `0.1.0`, then the owner's field test in a real app. Accept: the workflow's run green, the Central artifacts and
  the SwiftPM tag resolving, M10.4's script green against `0.1.0`.
  Done 2026-11-15:
  - The release gate passed after rerunning `e2e-kmp-android`, whose sample
    lost `runAll` on a relaunch (DEBT-0134).
  - The owner had the agent merge #415 and push the tag.
  - `Publish` passed: the Portal reported `PUBLISHED`, and the GitHub Release
    was created.
  - `scripts/consumers_check.sh 0.1.0` passed against Central and the GitHub
    tag.
  - Then the back-merge `main` → `dev` (#418, ADR-0219) and `dev` on
    `0.1.1-SNAPSHOT`.
- [x] **M10.5 Installation from the published version.** (`docs/readme-for-users`) Moved after
  M10.6 by the owner (2026-11-15): the README's text comes from testing
  `0.1.0` in a real app, and it is rewritten section by section with the
  owner. *Installation* drops *Until it is published*: the plugin line on
  Android, the coordinates on KMP, the package URL and an *Up to Next
  Minor Version* rule from `0.1.0` on iOS. ADR-0162 is superseded. SDD 02
  gets *Cutting a release*: version bump PR, release PR, the owner's merge,
  the tag, the workflow, how to check the Central deployment, the
  back-merge `main` → `dev` and the next `-SNAPSHOT` (ADR-0219).
  `CHANGELOG.md` already starts with `0.1.0` (M10.6). Accept: `make
  docs-check` green; each README step matches what M10.4's consumers do.
  Done 2026-11-15, widened by the owner: the README became a front page for
  app developers, and every detail moved to `docs/guide/` (ADR-0220).

- [x] **M10.R1 The write token on the tag only.** (`ci/publish-write-token-tag-only`) The M10
  milestone review's finding 5: the `central` job had `contents: write` on pull requests too,
  running that PR's own scripts. The GitHub Release moves to a `release` job that needs
  `central`, runs only on a tag and is the only one with `contents: write`.
  Accept: `publish.yml` parsed, `central` with the workflow's `contents: read`;
  `scripts/ruleset_check.sh` green.

## Outcome

### M10 milestone review

`code-review` at `medium` of `ae05cf5..d67a7c1` (#409 to #422): a static read of the diff, with
no test or workflow run by it. Ten findings; one is a security finding, each then read in the
files here. Findings 1 to 7 are correctness findings left open, so this PR waits for the owner.

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | The release notes' `awk` matches `## 0.1.1` as a prefix of `## 0.1.10` or `## 0.1.1-rc1`. | No: nothing re-releases an old version, and suffixes are refused | DEBT-0136 |
| 2 | A Portal slower than the 30 min timeout fails the job while it still publishes; no GitHub Release, and a rerun uploads again. | No: `0.1.0` took under 3 min | DEBT-0136 |
| 3 | The `pull_request` paths filter leaves out `gradle-plugin` and the modules' build files. | No: the release gate's `publish` job runs the same dry run before any tag | DEBT-0136 |
| 4 | An empty `APPMONITOR_SIGNING_KEY` counts as a key. | No: the workflow's empty value reaches only `publish_dry_run.sh`, which replaces it | DEBT-0136 |
| 5 | `contents: write` on pull request runs, which run the PR's scripts. | Security: yes, on every PR that changes the publication | M10.R1 |
| 6 | The consumers' release guard names only two root coordinates. | No: the TestKit consumer and the sample check the same rule on every PR | DEBT-0136 |
| 7 | `curl -fsS` drops the Portal's reason for a 4xx or 5xx. | No: a log, not a result | DEBT-0136 |
| 8 | `consumers_check.sh`'s header comment is 6 lines, over guideline 02's 4. | No: a comment | Fixed in M10.R1 |
| 9 | A fresh throwaway key per dry run invalidates the configuration cache. | No: a cost | DEBT-0136 |
| 10 | M10.2 says both `Hello`s are `0.1.0-dev` today. | No: stale text | Fixed in M10.R1 |

- **Shipped**: M10.1 signed publications with what Central requires (#410). M10.2 one version
  checked (#411). M10.3 the publish workflow (#412). M10.4 consumers of the published
  coordinates (#413). M10.6 `0.1.0` released: the release PR (#415), the tag, `Publish` green,
  consumers green against Central; then the back-merge rule (#417, #419), the back-merge
  (#418) and `0.1.1-SNAPSHOT` (#420). M10.5 the README for app developers and `docs/guide/`
  (#422). M10.R1 the write token on the tag only (this PR). Plan PRs #409 and #414; DEBT
  PRs #416 and #421.
- **Decisions**: ADR-0215 to ADR-0220. ADR-0162 superseded by ADR-0220.
- **Debt**: Paid DEBT-0117 (the KMP iOS consumer's Kotlin floor, measured). Created DEBT-0134
  (the KMP sample's `runAll` lost on a relaunch), DEBT-0135 (the Central bundle over the
  monthly file count), DEBT-0136 (this review).
- **Deviations**: the owner moved M10.6 before M10.5, so `0.1.0` was tested before the README
  was rewritten (#414). M10.5 widened into the whole README (ADR-0220). The owner had the agent
  merge #415 and push the tag. The release added a back-merge `main` → `dev` (ADR-0219), which
  the trailer check needed to skip (#419). M10.R1 was added by the review.
- **Lessons**: a release's first run met two limits the dry runs could not show: Central's
  monthly file count and `dev`'s squash-only rule. The next release plan should read the
  Portal's Usage Center before the tag, and count the bundle's files in the dry run.
