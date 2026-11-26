---
id: ADR-0231
title: Central's PUBLISHING ends the publish wait, and a published version is not uploaded again
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0231 — Central's PUBLISHING ends the publish wait, and a published version is not uploaded again

## Context

DEBT-0136 item 2 fired on `1.0.0`. The Portal validated the bundle in about 15 seconds
(14:30 UTC) and reported `PUBLISHING` until about 16:35 UTC, when
`appmonitor-okhttp-1.0.0.pom` first answered 200 on `repo1.maven.org`. The `central` job
gave up at its 30-minute timeout, so `release` and `tap` were skipped and were done by hand.
A rerun of `central` would have uploaded a version Central already had.

The Publisher API page (central.sonatype.org/publish/publish-portal-api, read 2026-11-26)
lists `PENDING`, `VALIDATING`, `VALIDATED`, `PUBLISHING`, `PUBLISHED` and `FAILED`.
`PUBLISHING`: "has been either automatically or manually published and is being uploaded to
Maven Central", so validation passed. The page documents no call that says whether a
version is already published, and not what a second upload of one returns.

## Options

1. **Stop waiting at `PUBLISHING`, and ask `repo1.maven.org` for the bundle's first `.pom`
   before uploading.** The GitHub Release and the tap follow minutes after the tag. The
   libraries can reach Maven Central hours after the release says so.
2. **Wait longer** (three hours). The release and the tap wait for the libraries, and a job
   holds a macOS runner for hours.
3. **Let `release` and `tap` skip `central`.** Packages would be released even when Central
   refuses the bundle.

## Decision

Option 1. A bundle that fails does so in validation, before `PUBLISHING`; after it, only
Central's own delay remains. The 30-minute timeout stays for the states before it.

## Consequences

- A release is complete in the time of its slowest package build, about 10 minutes.
- For up to a few hours after the release, a build resolving the new version from Maven
  Central fails; SwiftPM resolves the tag at once. `consumers_check.sh X.Y.Z` runs once
  `repo1.maven.org` serves the version.
- A rerun of a failed `release` or `tap` job, or of the whole run, passes `central`
  without uploading.
- A `.pom` already on `repo1.maven.org` with other contents passes too: Central keeps a
  published version for good, so there is nothing to upload anyway.
