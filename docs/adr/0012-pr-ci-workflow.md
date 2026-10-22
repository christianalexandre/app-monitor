---
id: ADR-0012
title: PR CI runs make check on ubuntu with the open-source Gradle cache
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0012 — PR CI runs make check on ubuntu with the open-source Gradle cache

## Context

PLAN-0001 M0.6 asks for a workflow on PRs to `dev` that runs `make check` and
the JVM tests on ubuntu. The repository is private, so every runner minute is
billed against the account quota; macOS minutes cost 10× and are reserved for
the release gate (roadmap M3).

Facts this decision rests on, read from the upstream sources on 2026-10-21:

- `gradle/actions/setup-gradle` v6 defaults to `cache-provider: enhanced`,
  described in its `action.yml` as "the full-featured commercial caching
  service (gradle-actions-caching)"; its README says it is "powered by
  proprietary technology" and only a "Free Preview" for private repositories.
  `cache-provider: basic` is "a simple open-source caching implementation
  based on GitHub Actions cache", MIT.
- The same `action.yml` makes `cache-read-only` default to `true` on any
  branch other than the default one. GitHub Actions caches written by a
  `pull_request` run are scoped to that PR's ref; only caches from the
  default branch (`dev`) are visible to every PR.
- `./gradlew build` already runs every JVM and Android host test, so
  `make check` covers "JVM tests" without a separate step.
- Kotlin/Native iOS targets cannot link or run tests on Linux; KGP skips
  those tasks there, so iOS simulator tests stay a macOS-only check
  (`docs/sdd/02_development_environment.md`, *Limits*).
- Latest releases on 2026-10-21: `actions/checkout` v7.0.1,
  `actions/setup-java` v6.1.0, `gradle/actions` v6.4.0,
  `actions/upload-artifact` v7.0.2.

## Options

1. **`make check` on ubuntu, `cache-provider: basic`, also on push to `dev`**
   — one job, same command as local. The `dev` push run writes the Gradle
   cache every PR reads. Costs one extra ubuntu run per merge.
2. **Same, PR-only trigger** — matches the plan text literally, but PR runs
   could never write a shared cache, so every run starts cold.
3. **Default `enhanced` cache** — faster restores, but a proprietary
   component whose terms for private repositories are a preview, i.e. a
   licensing/cost question for the owner (`AGENTS.md`).
4. **Separate jobs per module or target** — parallel, but each job pays
   checkout, JDK and Gradle start-up again; premature at today's build size
   (DEBT-0002).

## Decision

Option 1. `.github/workflows/ci.yml` runs `make check` on `ubuntu-latest`
with Temurin 21, `setup-gradle` with `cache-provider: basic`, on
`pull_request` to `dev` and on `push` to `dev`. Test reports are uploaded
only when the job fails.

## Consequences

- A red `make check` blocks the loop's merge step (`gh pr checks --watch`).
- iOS simulator tests are not run in CI until the release gate (M3); a PR
  that only breaks iOS passes CI and is caught by the local `make check`.
- If the open-source cache proves too slow, switching to `enhanced` is an
  owner decision, not an agent one.
