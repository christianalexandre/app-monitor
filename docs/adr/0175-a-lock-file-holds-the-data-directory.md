---
id: ADR-0175
title: A lock file holds the data directory
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0175 — A lock file holds the data directory

## Context

Two desktops on one data directory both open `appmonitor.db` (SDD 09). Each one
closes the other's open runs and sessions at launch (`closeOpen`) and runs
retention, so histories mix (DEBT-0062). The second one's device server fails
on the port, but `APPMONITOR_DATA_DIR` and the port are independent, and the
window behind a failed server keeps its store open. SQLite's own locks protect
the file's consistency, not who owns its contents.

The store's failure window offers "Clear all data" (ADR-0088), which here would
delete the running desktop's data.

## Options

1. **A row or a PID in the database.** Survives a crash, so it needs staleness
   rules; and it means writing to the file before knowing whether it may.
2. **`FileChannel.tryLock` on a file beside the database.** The OS frees it when
   the process ends, however it ends. It is advisory, which is enough: only
   App Monitor opens this directory.
3. **Bind a port as the lock.** A port is what a second desktop may want to move.

## Decision

Option 2, in `Storage.open` so every opener has it: `appmonitor.lock` in the
data directory, held until `close`. Another holder is
`DataDirectoryInUseException`. The app shows it in its own window with the
directory and what to do, without "Clear all data". The file stays after close;
its presence means nothing, only its lock does.

## Consequences

- A second desktop touches nothing: no run opened, no retention.
- A store left open in a test now refuses the next open in that JVM; a test
  standing in for a crash closes `directoryLock` itself.
- `clearDataFiles` leaves the empty lock file behind.
- On a file system without locks, `tryLock`'s `IOException` lands in the store's
  failure window. Not seen: the directory is under the user's home by default.
