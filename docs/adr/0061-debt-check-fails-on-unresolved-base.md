---
id: ADR-0061
title: The DEBT trigger check fails when its base does not resolve
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0061 — The DEBT trigger check fails when its base does not resolve

## Context

ADR-0051's `scripts/debt_check.sh` always exits 0. When `git diff
"$base...HEAD"` fails (`BASE=orign/dev`, or `origin/dev` never fetched),
git prints an error, the committed changes drop out, and the script still
says "no open DEBT trigger names a changed file". The `/next` self-check
reads that line as a clean result (first milestone review of PLAN-0004,
M3.R2). The same review found two matching gaps (DEBT-0049): `ios/Sources`
never matches `sdk/ios/Sources/x.swift`, and `Makefile` matches
`Makefile.bak` by stem.

## Options

1. **Exit non-zero when the base does not resolve or the diff fails** — the
   only failure that hides changes; findings stay informational.
2. **Fall back to uncommitted changes only, with a warning** — still prints
   a result that misses every commit on the branch.

## Decision

Option 1. Everything in ADR-0051 holds, except the exit status: the script
checks `git rev-parse --verify "$base^{commit}"` first and exits 2 with the
base named on stderr, and exits 2 if the merge-base diff fails; otherwise
it exits 0 whatever it lists. Matching changes too: a name with a `/`
matches a changed path that contains it as whole segments, anywhere; a name
without one matches the basename, or the basename without its last
extension unless the name is itself the whole basename of a tracked or
changed file. Supersedes ADR-0051.

## Consequences

`make debt-check` with a wrong `BASE`, or before the first fetch, stops
with an error instead of a false "nothing fired". Listed DEBTs still never
fail the target; the PR body answers them as before. A type named in a
trigger (`MonitorClient`) still matches `MonitorClient.kt`, but stops doing
so if a file named exactly `MonitorClient` is ever added.
