---
id: ADR-0059
title: The attribution trailer check runs on PRs to dev only
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0059 — The attribution trailer check runs on PRs to dev only

## Context

`pr-conventions.yml`'s `conventions` job fails when any commit between the
PR's base and head carries `Co-authored-by` or "Generated with"
(guidelines 03), and the `protect-main` ruleset requires `conventions` on
`main` (ADR-0053). On the first release PR, #132, it failed on
`1eaa36a` (#95): the PR's only commit was authored as the owner's gmail
address, the squash merge as the outlook one, so GitHub appended
`Co-authored-by: Christian Alexandre <…@gmail.com>` to the squash commit
it wrote on `dev` (`gh pr view 95 --json commits`; run 40057779806). The
check on #95 saw the PR's commit, not the squash commit, so it passed.
Rewriting `dev` needs a force-push, which guidelines 03 forbid.

## Options

1. **Skip the step when the base is `main`** — the commits of a release PR
   are squash commits GitHub wrote on `dev`, each from a PR that already
   ran the check; nothing new is authored there. The trailer GitHub adds
   at squash time stays invisible on both levels.
2. **Ignore co-author lines naming the owner** — keeps the check on
   release PRs, but hard-codes the owner's name or addresses in a public
   workflow and still misses nothing the first option catches, since an
   agent trailer would have been caught on the PR to `dev`.
3. **Rewrite `dev` without the trailer** — force-push; forbidden.

## Decision

Option 1: the `No attribution trailers` step runs only when
`github.base_ref` is not `main`. The `conventions` job name and its other
steps are unchanged, so the ruleset still matches it.

## Consequences

Release PRs from today's `dev` can pass `conventions`. A trailer GitHub
appends at squash time is never checked; it appears only when a PR's
commits are authored with an address other than the merging account's,
so commits keep one author address (`git config user.email`).
