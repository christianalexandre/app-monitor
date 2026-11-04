---
id: ADR-0198
title: A DEBT trigger path that exists from the root matches only there
status: Accepted
date: 2026-11-04
superseded_by:
---

# ADR-0198 — A DEBT trigger path that exists from the root matches only there

## Context

ADR-0061 matches a trigger name with a `/` against a changed path that contains it as whole
segments, anywhere, so `ios/Sources` finds `sdk/ios/Sources/x.swift`. Kotlin package
directories repeat module paths: DEBT-0059's `sdk/core` also matches the three
`sdk/core-no-op/src/*/kotlin/appmonitor/sdk/core/` files (`git ls-files`, 2026-11-04), so a
PR touching only the no-op lists a DEBT about `sdk/core`. DEBT-0050 recorded the noise.
The rest of ADR-0061 is not reopened: the exit codes, and names without a `/`.

## Options

1. **A name that exists from the root matches only from the root; any other as segments
   anywhere.** Fixes the case, and `ios/Sources` keeps working. A short name that is not a
   root path (`src/main`) still fires on every module; no open trigger names one that
   matches a tracked file.
2. **Every path name matches only from the root.** Simpler, but `ios/Sources` goes silent,
   the gap DEBT-0049 closed, and silence hides a debt where noise only costs a line.
3. **Leave it.** One extra line in a report whose findings never fail a target, but
   DEBT-0050's plan task asks to pay it or drop it on a repository case, and the case exists.

## Decision

Option 1. Tracked and changed files decide whether a name exists from the root, so a file
that the branch deletes or adds counts. Everything else in ADR-0061 holds. Supersedes
ADR-0061.

## Consequences

Module paths in triggers stop firing on packages that repeat them. A trigger naming a path
from the root stops firing on a nested copy of that path, which is what it means.
