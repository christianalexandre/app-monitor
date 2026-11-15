---
id: DEBT-0135
title: A release sends 1,068 files to Maven Central, over the free monthly file count
status: Open
date: 2026-11-15
trigger: The `release/1.0.0` branch is cut, or the Central Portal refuses a release for the monthly file count, or the next PR that changes `scripts/central_upload.sh`
paid_date:
---

# DEBT-0135 — A release sends 1,068 files to Maven Central, over the free monthly file count

## What is missing

The `0.1.0` bundle had 1,068 files. They come from 178 artifacts (89 jars, 33
POMs, 32 Gradle `.module` files, 10 klibs, 7 aars, 7 JSON files), each with a
`.asc` and four checksums (`.md5`, `.sha1`, `.sha256`, `.sha512`). Central's
free tier allows about 1,167 files per organization per calendar month
([publishing limits](https://central.sonatype.org/publish/maven-central-publishing-limits/)),
and the Portal reported the organization over it after `0.1.0`. A grace period
ends on 2026-12-14. After it, a release that keeps the organization over the
limit is refused. The organization's figures are in the
[Usage Center](https://central.sonatype.com/publishing/usage).

Ways to cut it:

- **Drop the `.sha256` and `.sha512` files from the bundle in
  `scripts/central_upload.sh`.** Central requires only `.md5` and `.sha1`. This
  takes a release to 712 files.
- **Stop publishing the `jvm` targets** that no consumer needs.

None of this changes what an app resolves.

## Why it was accepted

`0.1.0` is published, and nothing already published is affected. The owner
asked to shrink the release before `1.0.0` (2026-11-15). Until then, one
release per calendar month stays near the limit; two in a month are over it
even at 712 files.

## Trigger

The `release/1.0.0` branch is cut, or the Central Portal refuses a release
for the monthly file count, or the next PR that changes
`scripts/central_upload.sh`.

## How it was paid

> Filled when closing.
