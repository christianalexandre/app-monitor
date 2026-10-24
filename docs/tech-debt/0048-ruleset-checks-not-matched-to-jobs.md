---
id: DEBT-0048
title: The ruleset's required checks are not checked against the workflow job names
status: Open
date: 2026-10-24
trigger: The next PR that renames a job or changes a matrix in `release-gate.yml` or `pr-conventions.yml`, or a release PR that waits on a required check that never reports
paid_date:
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

> Filled when closing. What the solution taught, and what is left of it.
