---
id: ADR-0200
title: The release gate fails early and seeds its own cache
status: Accepted
date: 2026-11-05
superseded_by:
---

# ADR-0200 — The release gate fails early and seeds its own cache

## Context

A release gate that fails does so after its slowest job's 44 minutes, because
every job starts at once (ADR-0052). Measured on run `42045925738`, attempt 1,
on 2026-11-05:

| Job | Minutes |
| --- | --- |
| `e2e-ios-grpc` | 44 |
| `samples` | 37 |
| `e2e-kmp-ios` | 31 |
| `e2e-ios` | 14 |
| `publish` | 10 |
| `desktop (macos-latest)` | 9 |
| `package (macos-latest)` | 8 |
| `contract` | 7 |

The tests are not the time: 183 iOS tests take 6 s, and `e2e-ios-grpc` spends
42 of its minutes before its first assertion. Release PR #379's failures were
in `contract` (three runs) and `desktop (windows-latest)`, the jobs that end
first, and the gate still ran the rest each time.

The Gradle cache is also empty on every job that needs it most. The logs of the
macOS jobs read `Basic caching did not find an entry to restore`, `Entries: 0
restored, 0 saved`. `gh api .../actions/caches` lists 9 entries, all
`Linux`: only `ci.yml` writes one, since `setup-gradle` writes on the default
branch alone and the gate runs on PRs to `main`.

Not reopened: one job per check, the owner's free runners (ADR-0052), `basic`
over `enhanced` (ADR-0012), no cache of gRPC-Core's Xcode build (ADR-0163).

## Options

1. **Trim the tests** — little to gain: the suites are minutes of the gate's
   hours, and each test removed is coverage lost.
2. **Run the short jobs that fail most first, the rest only if they pass, and
   run the gate on `dev` on a schedule** — a failure costs about 9 minutes, not
   44, and the scheduled run writes the caches. A green gate takes about 9
   minutes longer until the caches pay it back, and a scheduled failure emails
   the owner.
3. **Cancel the other jobs on the first failure** — the same early stop with no
   added time, but GitHub has no such option across jobs, and a job calling the
   API to cancel its run needs more permissions than `contents: read`.

## Decision

Option 2. `contract` and `desktop` need `branch`; every other job needs both.
The gate also runs on `dev` on Mondays and Thursdays at 06:00 UTC, twice a week
because GitHub evicts a cache not read for 7 days.

## Consequences

- A gate that fails on those jobs fails in at most 9 minutes. A failure on
  a later job takes as long as before, plus those 9.
- `gh run rerun --failed` reruns a failed job and the jobs skipped after it.
- Skipped jobs are not required checks that pass unnoticed: the failed
  `contract` or `desktop` check still blocks the merge.
- The cache's gain is **not measured**: two dispatches of the gate on `dev` after
  this merges, one cold and one warm, give the number. GitHub's 10 GB per
  repository limit is a risk: `ci.yml`'s entries already add up to about 11 GB,
  and eviction takes the least recently read first. If a release PR's logs still
  read `0 restored`, this part did not work.
- A problem now shows within days, not at the next release: until now nothing
  ran the simulator suites between releases, and 83 commits went by unchecked.
