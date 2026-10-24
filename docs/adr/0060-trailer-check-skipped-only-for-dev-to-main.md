---
id: ADR-0060
title: The attribution trailer check is skipped only on dev → main PRs
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0060 — The attribution trailer check is skipped only on dev → main PRs

## Context

ADR-0059 skips the `No attribution trailers` step of `pr-conventions.yml`
on every PR whose base is `main`, because a `dev` → `main` PR only carries
squash commits from PRs that already ran the check. But
`scripts/branch_check.sh` also lets `release/x.y.z` target `main`
(guidelines 03), and a commit made on a release branch never went through a
PR to `dev`. A hotfix commit with a `Co-Authored-By:` trailer on
`release/1.0.1` would pass `conventions` (first milestone review of
PLAN-0004, M3.R1).

## Options

1. **Skip only when the head is `dev` and the base is `main`** — the case
   ADR-0059 justified; a `release/*` head is checked like any PR to `dev`.
2. **Keep ADR-0059 and forbid `release/*` heads** — changes guidelines 03
   and the release gate's `branch` job for a branch nobody has used yet,
   and leaves hotfixes without a path to `main`.

## Decision

Option 1: the step runs unless `github.base_ref` is `main` and
`github.head_ref` is `dev`. Supersedes ADR-0059.

## Consequences

`release/*` → `main` PRs run the trailer check over their own commits.
The check covers `main..head`, and `dev` → `main` is a merge commit
(guidelines 03), so `dev`'s squash commits already on `main` are not
rechecked. A release branch cut from `dev` before its last release PR
merged still carries ADR-0059's squash trailer and fails until cut again.
