---
id: DEBT-0002
title: make check runs the full Gradle build
status: Open
date: 2026-10-21
trigger: make check takes more than 3 minutes on a warm cache, or an end to end target (`e2e_android.sh`, M3.7's iOS one) joins `make check`
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

`make check` takes more than 3 minutes on a warm cache, or an end to end
target (`e2e_android.sh`, M3.7's iOS one) joins `make check`. Then split into a fast
`make check` (lint, compile, JVM unit tests) and a full `make check-full` run
before opening the PR.

The original trigger also named the first UI suite. It fired with PLAN-0001
M0.5 and was evaluated on 2026-10-21 on an Apple Silicon Mac: warm
`make check` 10.9 s, `./gradlew build --rerun-tasks` 16.0 s, and the Compose
UI test itself 0.65 s on an offscreen surface.
A split would still be guessing, so the UI half was removed from the
trigger.

The emulator half fired with PLAN-0004 M3.5 on 2026-10-23: `make e2e-android`
landed as its own target, outside `make check`, because it needs a booted
emulator and the internet. `make check` on that branch took 66 s with the
simulator tests of `make swift-sim` already in it, so the trigger now names
the event that would slow it: an end to end target added to it.

## How it was paid

> Filled when closing.
