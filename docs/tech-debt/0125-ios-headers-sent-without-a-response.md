---
id: DEBT-0125
title: iOS requests without a network response keep their resume headers
status: Open
date: 2026-11-04
trigger: A developer reports a header missing from a failed, held, mocked or background iOS request, or the next PR that changes `RequestFailed` in `network.proto`
paid_date:
---

# DEBT-0125 — iOS requests without a network response keep their resume headers

## What is missing

ADR-0189 reads the headers a request went out with when its response
arrives. A request that fails without a response (a timeout after sending),
a held or mocked task and a background session's task still show the
headers of the resume: no `Cookie` from the cookie storage, no
`User-Agent`.

## Why it was accepted

`RequestFailed` has no field for them, and the failed case would need a
second protocol change for a call the developer already sees failing. A
held task goes out through the SDK's own task, a mocked one never goes out,
and a background task's connection runs in `nsurlsessiond`; none was probed.

## Trigger

A developer reports a header missing from a failed, held, mocked or
background iOS request, or the next PR that changes `RequestFailed` in
`network.proto`.

## How it was paid

> Filled when closing.
