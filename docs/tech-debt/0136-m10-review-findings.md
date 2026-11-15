---
id: DEBT-0136
title: Findings of the M10 milestone review that normal use does not hit
status: Open
date: 2026-11-15
trigger: The next PR that touches a file its items name
paid_date:
---

# DEBT-0136 — Findings of the M10 milestone review that normal use does not hit

## What is missing

From the code-review of `ae05cf5..d67a7c1` (PLAN-0019's milestone review).
None of them is hit by a normal release:

1. **`.github/workflows/publish.yml`:** the release notes' `awk` matches a
   `CHANGELOG.md` heading by prefix. A tag `0.1.1` would also take a
   `## 0.1.10` or `## 0.1.1-rc1` section. Today nothing re-releases an old
   version, and `version_check.sh` refuses suffixes.
2. **`scripts/central_upload.sh`:** when the timeout (30 min) passes after an
   `AUTOMATIC` upload, the job fails while the Portal goes on publishing.
   No GitHub Release is created, and a rerun uploads a version Central
   already has. `0.1.0` was `PUBLISHED` in under 3 minutes.
3. **`scripts/central_upload.sh`:** `curl -fsS` drops the body of a 4xx or
   5xx answer, so the Portal's reason for refusing an upload is not logged.
4. **`.github/workflows/publish.yml`:** the `pull_request` paths filter leaves
   out `gradle-plugin/build.gradle.kts`, `gradle.properties` and the modules'
   build files, which also change the publication. The release gate's
   `publish` job runs the same dry run on every `dev` → `main` PR, before
   any tag.
5. **`build-logic/src/main/kotlin/appmonitor.maven-central.gradle.kts`:** an
   `APPMONITOR_SIGNING_KEY` set but empty counts as present, so signing fails.
   The workflow's empty value only reaches `make publish-dry-run`, which
   replaces it with a throwaway key.
6. **`scripts/consumers_check.sh`:** the release-build guard names only
   `appmonitor-core` and `appmonitor-okhttp`. A release resolving only
   `appmonitor-core-android`, or `appmonitor-ktor`, would pass. The TestKit
   consumer (ADR-0126) and `:samples:android:checkReleaseHasNoSdk` check the
   same rule on every PR.
7. **`scripts/publish_dry_run.sh`:** a fresh throwaway key in the environment
   on every run invalidates the configuration cache, and the key becomes
   one of its inputs.

## Why it was accepted

`0.1.0` was published through every one of these paths without hitting any.
Each fix is small, but none changes what a release does today.

## Trigger

The next PR that touches a file its items name: `.github/workflows/publish.yml`,
`scripts/central_upload.sh`,
`build-logic/src/main/kotlin/appmonitor.maven-central.gradle.kts`,
`scripts/consumers_check.sh`, `scripts/publish_dry_run.sh`.

## How it was paid

> Filled when closing.
