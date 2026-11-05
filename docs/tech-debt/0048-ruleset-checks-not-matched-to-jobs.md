---
id: DEBT-0048
title: The ruleset's required checks are not checked against the workflow job names
status: Paid
date: 2026-10-24
trigger: The next PR that renames a job or changes a matrix in `release-gate.yml` or `pr-conventions.yml`, or a release PR that waits on a required check that never reports
paid_date: 2026-10-30
---

# DEBT-0048 — The ruleset's required checks are not checked against the workflow job names

## What is missing

`.github/rulesets/protect-main.json` lists the release gate's check names by
hand, matrix values included (`package (macos-latest, dmg, packageDmg)`).
Nothing compares them with the jobs of `release-gate.yml` and
`pr-conventions.yml`, nor the file with the live ruleset. Symptom: a release
PR whose checks are all green stays blocked, "Expected — Waiting for status
to be reported" on a name no job produces.

## Why it was accepted

The names change only when a job does, and ADR-0053 says the ruleset changes
in the same PR. Deriving matrix check names from the workflow YAML in bash
is more code than the thirteen names it would protect.

## Trigger

The next PR that renames a job or changes a matrix in `release-gate.yml` or
`pr-conventions.yml`, or a release PR that waits on a required check that
never reports.

## How it was paid

`ci/release-gate-cache` (PLAN-0015 H5.1) added `scripts/ruleset_check.sh`
to `make scripts-test`: it reads the workflows with `ruby`'s YAML, names
matrix jobs as GitHub does (checked against run 41092309585's check names),
and fails when a ruleset requires a name no workflow reports on pull
requests to that branch, or a release gate job is not required on `main`.
`test_ruleset_check.sh` edits a copy of `.github/` five ways. Left: the
file against the live ruleset is still the `gh api` check in `AGENTS.md`;
without `ruby` the script prints `SKIPPED`.
