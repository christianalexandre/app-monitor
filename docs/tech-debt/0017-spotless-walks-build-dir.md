---
id: DEBT-0017
title: Spotless walks build directories while tests write them
status: Open
date: 2026-10-22
trigger: The same spotless failure on a build file appears a second time, locally or in CI
paid_date:
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

> Filled when closing.
