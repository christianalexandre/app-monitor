---
id: ADR-0215
title: Maven Central through maven-publish, Gradle signing and the Portal's bundle API
status: Accepted
date: 2026-11-14
superseded_by:
---

# ADR-0215 — Maven Central through maven-publish, Gradle signing and the Portal's bundle API

## Context

PLAN-0019 M10.1: every publication of the dry-run (ADR-0058) and the Gradle
plugin with its marker (ADR-0093 E.1) must be what Maven Central accepts.
ADR-0058 chose Gradle's `maven-publish` and left the upload path to M10.
Already decided: coordinates (ADR-0054), one version for every SDK
(PLAN-0019), the plugin published with the SDK.

Evidence:

- Central's requirements (central.sonatype.org/publish/requirements, read
  2026-11-14): a `-sources.jar` and a `-javadoc.jar` beside every artifact
  whose packaging is not `pom`, placeholder jars allowed; `.md5` and `.sha1`
  for every file; an `.asc` signature for every file except checksums; POM
  with name, description, URL, a license, a developer and SCM `connection`,
  `developerConnection`, `url`; no `-SNAPSHOT`.
- The Portal's API (central.sonatype.org/publish/publish-portal-api, read
  2026-11-14): `POST /api/v1/publisher/upload` with a multipart `bundle`
  (a zip of a Maven repository layout), `Authorization: Bearer
  base64(username:token)`, `publishingType=AUTOMATIC` or `USER_MANAGED`;
  `POST /api/v1/publisher/status?id=` reports `PUBLISHED` or `FAILED`.
- `maven-publish` to a file repository already writes `.md5`, `.sha1`,
  `.sha256` and `.sha512` (seen in `build/maven-dry-run`).
- Gradle's `signing` plugin with `useInMemoryPgpKeys` and
  `sign(publishing.publications)` signed all 178 files of the 22 SDK and
  plugin publications, `.module` files included, under the configuration
  cache (Gradle 9.7.1); `gpg --verify` accepts each one.
- The KMP publications have sources jars and no javadoc jar; the plugin's
  `pluginMaven` had neither; its marker is `<packaging>pom</packaging>`.

## Options

1. **`maven-publish` + `signing` + the Portal's bundle API by `curl`**
   (chosen) — no new plugin: the convention adds a placeholder javadoc jar
   per publication, sources for the plugin, the POM and signing; the
   publish workflow zips the very repository the dry-run checks and posts
   it. The upload is a dozen lines of shell that M10.3 owns.
2. **`com.vanniktech.maven.publish`** — uploads to the Portal itself and
   adds javadoc jars; replaces ADR-0058's convention and the plugin's
   publication setup with its own defaults, and the uploaded files are not
   the ones the dry-run checked.
3. **JReleaser** — another tool with its own configuration file and a
   staging step, for the same zip-and-post.

## Decision

Option 1. A convention `appmonitor.maven-central` holds what Central
requires (placeholder javadoc jar on every non-marker publication, sources
on the plugin, POM, the `dryRun` repository, `appmonitor.mavenRepo` moving
it) and signs only when `APPMONITOR_SIGNING_KEY` (ASCII-armored secret key)
is set, with `APPMONITOR_SIGNING_PASSWORD`; the key is read from the
environment, never from a file. `appmonitor.publish` applies it and keeps
the SDK coordinates; `gradle-plugin` applies it directly.
`scripts/publish_dry_run.sh` signs with a throwaway ed25519 key in
`build/throwaway-gnupg` and fails on a missing sources or javadoc jar, a
missing `.asc`, `.md5` or `.sha1`, or a signature `gpg` does not verify.

## Consequences

- `make publish-dry-run` needs `gpg` (GitHub's macOS images ship it; on a
  Mac, `brew install gnupg`); it takes ≈53 s warm instead of ≈35 s, and is
  still not in `make check`.
- Without the variables nothing is signed, so `publishToMavenLocal`, the
  TestKit tests and `make check` are unchanged.
- The javadoc jars are empty; Dokka is not wired. An app developer reads
  the API in the README and the sources jars.
- M10.3's workflow zips `build/maven-dry-run` after a signed publish of the
  release version and posts it; the Portal's validation is the last check.
