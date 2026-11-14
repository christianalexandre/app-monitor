---
id: ADR-0217
title: The publish workflow signs on a main tag and uploads with publishingType AUTOMATIC; PRs run it dry
status: Accepted
date: 2026-11-14
superseded_by:
---

# ADR-0217 — The publish workflow signs on a main tag and uploads with publishingType AUTOMATIC; PRs run it dry

## Context

PLAN-0019 M10.3. ADR-0215 chose the Portal's bundle API by `curl` and
signing from `APPMONITOR_SIGNING_KEY`; ADR-0216 the version check. The
release is the owner's: they merge `dev` → `main` and push the tag
(`docs/guidelines/03_git_and_pull_requests.md`). SwiftPM resolves a tag of
the repository with no upload (ADR-0054).

Evidence:

- The Portal's API (central.sonatype.org/publish/publish-portal-api, read
  2026-11-14): `publishingType=AUTOMATIC` publishes once validation passes,
  `USER_MANAGED` waits for a click in the Portal; status `PUBLISHED` or
  `FAILED` with `errors`.
- GitHub runs a `workflow_dispatch` only for a workflow on the default
  branch, so a new workflow cannot be dispatched from its own PR branch
  (docs.github.com, "Manually running a workflow").
- `gpg --batch --import` imports a passphrase-protected secret key without
  asking for it (gpg 2.5.24, tried on a throwaway key).

## Options

Upload mode:

1. **`AUTOMATIC`** (chosen) — the tag is already the owner's deliberate
   act, and the same files passed `make publish-dry-run`'s checks in the
   same job; a validation failure publishes nothing.
2. **`USER_MANAGED`** — one more click in the Portal for every release, and
   the workflow cannot tell when it is done.

Proving the workflow before the first tag:

3. **A `pull_request` trigger on the publication's files** (chosen) — the
   job runs dry, without secrets, on the PR that changes it, this one
   included, and uploads the bundle as an artifact.
4. **`workflow_dispatch` only** — cannot run before the file is on `dev`.

## Decision

Options 1 and 3. `.github/workflows/publish.yml`, job `central`, on
macOS. On an `X.Y.Z` tag it fails unless the tagged commit is on `main` and
`scripts/version_check.sh --release` passes. Then `make publish-dry-run`
signs with the release key from the secrets and checks every file.
`scripts/central_upload.sh` zips `build/maven-dry-run` without
`maven-metadata.xml`, uploads it as `AUTOMATIC` and waits for `PUBLISHED`.
Finally `gh release create` uses the tag's section of `CHANGELOG.md`, or
GitHub's generated notes. On a pull request to `dev` that changes the
publication it runs the same steps with a throwaway key and keeps the
bundle as the `central-bundle` artifact for 7 days.

Secrets: `APPMONITOR_SIGNING_KEY`, `APPMONITOR_SIGNING_PASSWORD`,
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (a Portal user token),
read only in the steps of a tag run.

## Consequences

- A release is: the owner merges `release/x.y.z` → `main`, then pushes the
  tag; the rest is the workflow. Central keeps a published version
  forever, so a wrong one is fixed by the next version.
- A PR touching the publication's files gains a macOS job (≈5 min), not
  required by the `dev` ruleset.
- `scripts/central_upload.sh` is tested against a stub Portal in
  `make scripts-test`; the real Portal is first reached by the `0.1.0` tag.
