---
id: ADR-0080
title: Without hard links, sdk/core publishes the device id under a file lock
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0080 — Without hard links, sdk/core publishes the device id under a file lock

## Context

ADR-0078 publishes the device id file by an exclusive hard link, and falls
back to `rename` when the file system cannot link, accepting the last
writer wins there. The second milestone review (PLAN-0005 M4.R7) found that
the fallback is the normal path on Android: SELinux denies `link` to every
untrusted app (`neverallow … file_type:file link` in AOSP's
`private/app_neverallows.te`). Two writers on a first launch
(`sdk/android-okhttp` and `sdk/ktor` in one app, or two processes) each
rename their own file, and the first can re-read its own id before the
second rename lands: the install shows two ids. The JVM host test links, so
it never saw it.

Evidence:

- AOSP `public/global_macros`: `r_file_perms` is `{ getattr open read
  ioctl lock map watch watch_reads }`, and `create_file_perms` adds
  `create rename setattr unlink`, so an app may `lock` and `rename` its own
  data files. SQLite's file locks in app storage rely on the same.
- A racing-writers test with the link refused (8 writers, 50 rounds) fails
  on the JVM when the fallback renames without a lock, and passes with it.

No Swift SDK writes on Android, so nothing outside `sdk/core` has to agree on
the lock there. On iOS the Swift SDK links (ADR-0078), and APFS links.

## Options

1. **Keep link-or-rename** — last writer wins on every Android first launch.
2. **`O_EXCL` create of the file itself, then write the id** — a reader can
   see the empty file between create and write, and a crash there leaves an
   empty file that blocks the next writers until something replaces it.
3. **An advisory lock on `<file>.lock` around re-read and rename** — the
   kernel drops the lock when its holder dies, so nothing goes stale; readers
   outside the lock still only see whole files, since the id arrives by
   `rename`. Leaves a `<file>.lock` beside the file, which cannot be deleted
   safely while another writer may be waiting on it.

## Decision

Option 3. `publish` still tries `linkExclusive` first; when it returns null
(the platform or file system cannot link), it takes `withFileLock` on
`<file>.lock`, and renames its temporary file over the file only if the file
still holds no UUID. On Android `linkExclusive` is always null, so the
Android path no longer calls `link`. The lock is `FileChannel.lock()` on the
JVM and Android, inside one process-wide monitor since a JVM throws
`OverlappingFileLockException` instead of waiting on its own lock, and
`flock(2)` on iOS.

## Consequences

- Writers that cannot link agree on one id, a corrupt file included: the
  re-read under the lock sees the first writer's replacement.
- Android installs keep an empty `appmonitor-device-id.lock` in
  `noBackupFilesDir`.
- ADR-0078's "a file system without hard links falls back to rename, with
  last-writer-wins" no longer holds for `sdk/core`; it still holds for the
  Swift SDK.
- `Os.link` and the API 26 split are gone from `sdk/core`'s Android code.
- The lock and `createParentDirectories` are one copy in
  `sdk/core/src/jvmAndAndroidMain`, a `jvmAndAndroid` group added to the
  default hierarchy in `sdk/core/build.gradle.kts`.
