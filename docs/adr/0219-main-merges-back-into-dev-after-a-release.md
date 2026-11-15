---
id: ADR-0219
title: main merges back into dev after a release, with a merge commit
status: Accepted
date: 2026-11-15
superseded_by:
---

# ADR-0219 — main merges back into dev after a release, with a merge commit

## Context

ADR-0216 makes the version bump a commit on `release/x.y.z`, so after the
release PR, `main` holds a commit `dev` does not: `39385a4 release: 0.1.0`
under the merge commit of #415. `dev` keeps `0.1.0-SNAPSHOT` on the same
lines. The next release branch changes those lines again, from the old
merge base, so its PR to `main` conflicts.

`protect-dev.json` allowed only squash. Squashing `main` into `dev` copies
the content without the ancestry, so the next release conflicts anyway.
`branch_check.sh` refused `main` as the head of a PR to `dev`.

The owner asked for the common practice (2026-11-15). In git-flow, the
release merges into `main` and back into the development branch, both with
merge commits.

## Options

1. **Back-merge `main` → `dev` with a merge commit.** `dev` allows squash and
   merge; the guideline keeps squash for work branches. One PR per release,
   no conflict at the next one.
2. **Bump the version on `dev` before the release PR.** `main` would never
   get a commit `dev` lacks, but `dev` would carry a version without
   `-SNAPSHOT` between the bump and the tag. That is what ADR-0216 avoided.
3. **Resolve the conflict in each release branch.** Nothing changes now, but
   every release starts with a hand merge of `origin/main`.

## Decision

Option 1, as the owner chose. `protect-dev.json` allows `squash` and
`merge`. A PR to `dev` whose head is `main` is the back-merge: merge commit,
opened after the tag. Every other PR to `dev` stays squash (guidelines 03).
The loop passes `--squash`, so it does not change. Rebase merge stays off.

## Consequences

- After each release, one more PR: `main` → `dev`. Then a PR on `dev` moves
  the version to the next `-SNAPSHOT`.
- The merge commit button now shows on `dev`. Only the guideline keeps it
  for the back-merge; nothing enforces it.
- `branch_check.sh` accepts `main` → `dev`.
