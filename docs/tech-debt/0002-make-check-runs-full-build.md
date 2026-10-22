---
id: DEBT-0002
title: make check runs the full Gradle build
status: Open
date: 2026-10-21
trigger: make check takes more than 3 minutes on a warm cache, or the first UI or e2e test suite lands
paid_date:
---

# DEBT-0002 — make check runs the full Gradle build

## What is missing

`make check` runs `./gradlew build`: every module, every target (JVM, Android
host, iOS simulator) and every test. The loop runs it at least once per
iteration. There is no fast gate separate from a full one.

## Why it was accepted

With the cache warm the whole build takes seconds today; a split now would be
guessing which tests are slow.

## Trigger

`make check` takes more than 3 minutes on a warm cache, or the first UI or
end-to-end test suite lands (M0.5 Compose UI test, M1 emulator tests). Then
split into a fast `make check` (lint, compile, JVM unit tests) and a full
`make check-full` run before opening the PR.

## How it was paid

> Filled when closing.
