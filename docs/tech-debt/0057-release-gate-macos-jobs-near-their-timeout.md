---
id: DEBT-0057
title: The release gate's macOS jobs run close to their timeouts
status: Paid
date: 2026-10-24
trigger: A release gate run where `contract` or `e2e-kmp-ios` in `release-gate.yml` times out or takes more than 50 minutes
paid_date: 2026-10-24
---

# DEBT-0057 — The release gate's macOS jobs run close to their timeouts

## What is missing

`contract` (`make swift`, `timeout-minutes: 60`) took 30.4 min in run
40110565852 and 43.1 min in run 40113961385, since `make swift` builds
`samples/kmp` in two configurations (ADR-0073). `e2e-kmp-ios`
(`timeout-minutes: 40`) took 22.0 min against ADR-0065's ≈10–12 estimate,
most of it the Kotlin/Native framework build. The gate's wall time is now
`contract`'s. Symptom: a job cancelled at its timeout with no failed step.

## Why it was accepted

Both runs were green and the minutes are free on a public repository
(ADR-0052). Two measurements are not enough to tell runner variance from a
trend, and the fixes (a Kotlin/Native build cache shared across jobs, or
moving `make kmp-sample` out of `contract`) change what each job proves.

## Trigger

A release gate run where `contract` or `e2e-kmp-ios` in `release-gate.yml`
times out or takes more than 50 minutes.

## How it was paid

ADR-0076 moved `make kmp-sample` out of `contract` into a parallel
`samples` job (`make samples`), so `contract` runs only the Swift host gates,
the simulator tests and `:protocol:allTests`. Left: `e2e-kmp-ios` still
builds the Kotlin/Native framework cold (22.0 min in run 40113961385).
