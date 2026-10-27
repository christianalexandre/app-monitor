---
id: DEBT-0080
title: The iOS sample builds gRPC-Core cold in every gate job that builds it
status: Open
date: 2026-10-25
trigger: A release gate run where `samples-ios` or `e2e-ios` in `release-gate.yml` times out or takes more than 35 minutes
paid_date:
---

# DEBT-0080 — The iOS sample builds gRPC-Core cold in every gate job that builds it

## What is missing

`samples/ios` depends on `grpc/grpc-ios` (ADR-0109), a C++ build: cold
on this machine, Debug 254 s against 12 s before it and Release 128 s
against 18 s. The gate's `samples` (`timeout-minutes: 50`, Debug and
Release) and `e2e-ios` (`timeout-minutes: 40`, Debug) jobs build it with
no cache. Symptom: one of them cancelled at its timeout, or much slower
than before PLAN-0009 M8.8.

## Why it was accepted

No gate run has measured it on a hosted runner yet, and the minutes are
free on a public repository (ADR-0052). The fixes (caching the derived
data of `build/ios-sample` keyed on the package's resolved version, or a
prebuilt gRPC-Core) add a cache whose invalidation the gate must trust.

## Trigger

A release gate run where `samples-ios` or `e2e-ios` in `release-gate.yml`
times out or takes more than 35 minutes.

Fired by #293's release gate (run 40412489599): `samples` was cancelled at
its 50 minute timeout, in "Sample apps, debug and release" (`make samples`:
Android, iOS and KMP samples in one job). ADR-0154 moves the iOS sample to
a `samples-ios` job beside it; the cache of the gRPC-Core build is still
not done, so the trigger moves to `samples-ios`. `e2e-ios` took 28 min in
the same run.

## How it was paid

> Filled when closing.
