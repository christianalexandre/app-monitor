---
id: DEBT-0085
title: An iOS task held at a breakpoint skips the app's challenge and redirect delegate
status: Paid
date: 2026-10-25
trigger: The first app whose held task fails on a pinned certificate, a client certificate or a redirect it refuses, or the next PR that changes `HoldProtocol` in `sdk/ios/Sources/AppMonitor/Breakpoints.swift`
paid_date: 2026-10-25
---

# DEBT-0085 — An iOS task held at a breakpoint skips the app's challenge and redirect delegate

## What is missing

`HoldProtocol` sends a held task on through a session of its own, copied
from the task's configuration (ADR-0125 B.1). Its authentication
challenges get default handling and its redirects are followed, so the
app's `didReceive challenge` and `willPerformHTTPRedirection` are never
asked. Symptom: a task that works without a breakpoint fails TLS, or
lands on a page the app would have refused, once a breakpoint matches it.

## Why it was accepted

Only a task a developer put a breakpoint on is affected, in a debug
build. Forwarding means turning each challenge into one the protocol's
client hands the app's delegate, and each redirect into
`wasRedirectedTo`, which restarts the load and must not hold it again.

## Trigger

The first app whose held task fails on a pinned certificate, a client
certificate or a redirect it refuses, or the next PR that changes
`HoldProtocol` in `sdk/ios/Sources/AppMonitor/Breakpoints.swift`.

## How it was paid

PLAN-0011 H1.3, ADR-0130. `HoldProtocol`'s session delegate asks the
app's delegates itself, on the app's delegate queue, in URLSession's
order, and hands their answer to the inner task; a refused redirect ends
the inner task on the `3xx`. Forwarding through the protocol's client was
not used: a redirect restarts the load, which would hold it again. Left:
a session-wide challenge (server trust, client certificate) is not
tested, the Swift tests have no TLS origin.
