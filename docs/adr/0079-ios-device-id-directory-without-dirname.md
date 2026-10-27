---
id: ADR-0079
title: sdk/core on iOS creates the device id directory with NSFileManager, not kotlinx-io
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0079 — sdk/core on iOS creates the device id directory with NSFileManager, not kotlinx-io

## Context

ADR-0078's race test (8 writers on an empty directory, 50 rounds) failed in
about half its runs on the iOS simulator and never on the JVM or the
Android host (PLAN-0005 M4.R6). A writer's `publish` threw `Failed to open
<file>.<uuid> with No such file or directory`, and `deviceId` returned its
own id, which never reached the file: one install, two ids.

Evidence, from instrumenting `publish` on the simulator:

- The round directory existed right after the failure, and opening the same
  temporary file again succeeded: the failure is transient.
- The directories `publish` walked up from `file.parent` started one level
  too high: the round directory was never the one created.
- kotlinx-io 0.9.1 (the version Ktor resolves, and the latest release)
  implements `Path.parent` and `Path.name` on Apple with `dirname(3)` and
  `basename(3)` (`core/apple/src/files/FileSystemApple.kt`), and
  `createDirectories` walks up with `Path.parent`. Darwin's `man 3 dirname`:
  "returns a pointer to internal storage space allocated on the first call
  that will be overwritten by subsequent calls. dirname_r() is therefore
  preferred for threaded applications."
- A plain C program and a Kotlin/Native test racing `mkdir` and `open` in
  the same directory, without `Path.parent`, never failed.

So two threads asking for a `Path.parent` at once can each get the other's
answer. `make check` runs on Linux in PR CI, so only a local
`:sdk:core:allTests` on macOS sees it.

## Options

1. **Retry `publish`** — hides the symptom; a wrong parent can also make
   `createDirectories` create a directory the caller never named.
2. **Lock around `Path.parent` calls** — covers `sdk/core` only, while any
   other kotlinx-io user in the app (Ktor included) calls `dirname` on its
   own threads.
3. **On iOS, take the parent by string and create it with
   `NSFileManager.createDirectoryAtPath(withIntermediateDirectories = true)`**,
   which uses no shared buffer and does not fail when the directory exists
   or another thread creates it first. The JVM and Android keep
   `Path.parent` and `createDirectories`, backed by `java.io.File`.

## Decision

Option 3: `deviceId` creates the file's parent through the platform function
`createParentDirectories`; the iOS one never calls kotlinx-io's `Path.parent`,
`Path.name` or `createDirectories`.

## Consequences

- The race test passes 10 runs in a row on the simulator (it passed 1 in 10
  with only a race-tolerant `createDirectories`).
- The rest of kotlinx-io's Apple `Path` stays unsafe across threads; any new
  use of `Path.parent`, `Path.name` or `createDirectories` in `sdk/core` on iOS
  brings the bug back. DEBT-0059 tracks it until kotlinx-io fixes it.
