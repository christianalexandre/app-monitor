---
id: ADR-0078
title: The device id file is published by an exclusive hard link
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0078 — The device id file is published by an exclusive hard link

## Context

ADR-0064 B.1 has `sdk/core` on iOS and the Swift SDK share one file,
`Application Support/appmonitor-device-id`, so the desktop sees one install.
The milestone review (PLAN-0005 M4.R2) found that neither side coordinates:
`sdk/core` truncated and rewrote the file in place, the Swift SDK wrote it
with `.atomic` (a rename that replaces). On a first launch that starts both
(`KmpSampleApp.init`), each can generate its own UUID and keep it, and a
reader can see `sdk/core`'s truncated file and generate a third.

Evidence: the race test added by M4.R2 (8 writers on an empty directory,
50 rounds) failed in round 0 on the JVM against the old `deviceId`, and in
all 50 rounds against the old Swift `deviceID(file:)`.

The two SDKs run in one process but share no lock: one is Kotlin/Native,
the other Swift. Only the file system can arbitrate.

## Options

1. **Lock file** (`O_EXCL` create, or `flock`) around read-or-create — both
   sides must agree on the lock, and a crash while holding an `O_EXCL` lock
   leaves it stale.
2. **Write a temporary file beside the target, then `link(2)` it to the
   target** — `link` fails with `EEXIST` when the target exists, so the
   first complete file wins and nobody ever sees a partial one; every
   writer re-reads and returns what the file holds. No lock, nothing stale
   (the temporary file is unlinked either way). Needs a file system with
   hard links: APFS and Android's app storage have them.
3. **`rename` only (atomic replace)** — no partial file, but the last
   writer wins, so earlier writers keep ids nobody else has.

## Decision

Option 2 on both sides. `sdk/core` `deviceId` and the Swift SDK's
`deviceID(file:)` read the file; when it holds no UUID, they write a new one
to `<file>.<uuid>`, hard link it to the file, and return what the file
holds. When the link fails with `EEXIST` and the file still holds no UUID
(a corrupt file), or the file system cannot link, they `rename` over it.
`sdk/core`'s link is `Files.createLink` on the JVM and Android API 26+,
`Os.link` on Android 24–25, `link(2)` on iOS.

## Consequences

- Any number of writers, in either SDK, agree on one id; the race test runs
  in `:sdk:core:allTests` (JVM, Android host, simulator) and
  `swift test --package-path sdk/ios`.
- Two writers replacing the same corrupt file can still end with different
  ids; a corrupt file needs something outside the SDKs to write it.
- A file system without hard links falls back to rename, with option 3's
  last-writer-wins.
