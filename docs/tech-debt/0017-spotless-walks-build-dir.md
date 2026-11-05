---
id: DEBT-0017
title: Spotless walks build directories while tests write them
status: Paid
date: 2026-10-22
trigger: The same spotless failure on a build file appears a second time, locally or in CI
paid_date: 2026-10-31
---

# DEBT-0017 — Spotless walks build directories while tests write them

## What is missing

`appmonitor.lint` sets `target("src/**/*.kt")` with
`targetExclude("**/build/**")`, but Gradle still walks the project tree to
resolve it. Once, during `make check`, `:desktop:app:spotlessKotlin` failed
with "Could not read path `desktop/app/build/test-results/test/binary/output-events.bin`"
— a file the test task removed while the tree was being walked. The rerun
was green.

## Why it was accepted

Seen once, right after a separate `:desktop:app:test` run, and a rerun passes.
Narrowing the target to a `fileTree("src")` changes every module's lint
configuration for a failure not yet reproduced.

## Trigger

The same spotless failure on a build file appears a second time, locally or
in CI.

## How it was paid

Paid by PLAN-0015 H5.2e (`docs/spotless-reads-sources-only`). #148 had
already replaced the pattern with `target(fileTree("src") { include("**/*.kt") })`
in `appmonitor.lint.gradle.kts`, without closing this item. Reproduced
with two shell loops writing and deleting files in `desktop/app/build/`
and `desktop/app/build/test-results/` while
`./gradlew :desktop:app:spotlessKotlin :desktop:app:spotlessKotlinGradle --rerun-tasks`
ran in a loop: with the old `target("src/**/*.kt")` and
`targetExclude("**/build/**")`, 3 of 10 runs failed with
`NoSuchFileException` on a churned file, hashed for the task's
`lineEndingsPolicy`; with the current target, 0 of 50. The root
project's string targets (`build-logic/src/**/*.kt`…), under the same
load plus `build-logic/build/`, passed 20 of 20.
