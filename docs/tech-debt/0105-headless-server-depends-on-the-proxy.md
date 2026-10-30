---
id: DEBT-0105
title: The headless server module depends on the proxy
status: Open
date: 2026-10-30
trigger: "The headless server becomes a distributed artifact, or `desktop/proxy/build.gradle.kts` needs a dependency on `desktop/server`"
paid_date:
---

# DEBT-0105 — The headless server module depends on the proxy

## What is missing

`desktop/server/build.gradle.kts` has `implementation(project(":desktop:proxy"))`
only for `make server`'s `--proxy DIR` flag (`Main.kt` builds a
`ProxyCa` and a `ProxyServer`, ADR-0109). So the device server, which
`desktop/app` and `tools/fake-client`'s tests also use, carries Netty and
BouncyCastle for a test option. `desktop/proxy` can never depend on
`desktop/server` without a cycle.

## Why it was accepted

`--proxy` exists for `make e2e-ios`, whose gRPC-Core call goes through
the proxy (ADR-0109), and `Main.kt` was the one headless entry point
already started by the end to end scripts. The headless server is not
shipped: the desktop app depends on both modules anyway.

## Trigger

The headless server becomes a distributed artifact, or
`desktop/proxy/build.gradle.kts` needs a dependency on
`desktop/server`. Then `Main.kt` and its flags move to a tools module
(beside `tools/e2e-target`) that depends on both, and `desktop/server`
keeps only the device server.

## How it was paid

> Filled when closing.
