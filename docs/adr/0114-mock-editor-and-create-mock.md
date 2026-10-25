---
id: ADR-0114
title: The window's mock editor and the create_mock tool
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0114 — The window's mock editor and the create_mock tool

## Context

ADR-0110 decided that rules are made in the window, from an exchange or
an empty form, and by an MCP tool whose rules stay pending until the
owner confirms them in the window (F.1, ADR-0005). ADR-0113 decided how
the store keeps a rule and that a set past one frame is cut at the first
rule that does not fit, which the editor must show (C.1). Left open: how
the window lists, confirms and fills rules, and the tool's arguments.
This is inside the agreed architecture (`AGENTS.md`); the security
side (MCP rules pending) is ADR-0110 F.1, not reopened.

Evidence, 2026-10-25:

- `Storage` has `createMock`, `updateMock`, `confirmMock`, `deleteMock`
  and `observeMocks`; `updateMock` never clears `pending`, only
  `confirmMock` does (`Mock.sq`, `Ingest.kt`). No query moves a rule's
  `position`.
- A stored body is what the device captured, and that differs by SDK:
  OkHttp's interceptor copies the body as the app reads it (SDD 05),
  while `sdk/ktor` captures a response its `ContentEncoding` plugin
  decodes still encoded (SDD 08), under a `Content-Encoding: gzip`
  header. A mock is answered above the client's decoding, so the app
  reads the mock's body as given.
- MCP tool arguments are JSON; `save_issue` takes flat optional fields
  and refuses a malformed one with one sentence (SDD 10).

## Options

### A. Where rules are listed

1. **A "Mocks" panel beside "Issues", every app's rules in match order,
   its button counting the rules waiting for confirmation.** A pending
   rule says it came from MCP and is not sent, with "Confirm" beside it;
   an edit in the window does not confirm it. An active rule past the
   frame is marked "not sent: past one frame".
2. **Rules under each device.** A rule belongs to an app, not to a
   session, and an app with no device connected would hide its rules.

### B. "Mock this" from an exchange

1. **A new rule filled from the exchange**: its session's app id, the
   method, host and path (no query), and the response's status, headers
   and stored body; a failed exchange fills a failure with its error.
   `Content-Length`, `Transfer-Encoding` and `Content-Encoding` are left
   out, and a body still gzip (its magic bytes, `1f 8b`) is gunzipped:
   the mock then carries the body the app reads, whichever SDK captured
   it. A body that does not gunzip (cut by the device) is kept as it is.
   A body that is not UTF-8 is kept as bytes, shown by its size, and can
   only be cleared.
2. **Every header and the body as stored.** A Ktor capture would give a
   `gzip` body the app's code never sees decoded, and an OkHttp one a
   `gzip` header over a body that is not.

### C. The tool's arguments

1. **`create_mock` with flat fields**: `app_id`, `name`, `host`, `path`
   required; `method`, `delay_ms`; one of `status_code` (with `headers`
   and `body` as text or `body_base64`) or `failure_message`. It answers
   the rule as stored, `pending: true`. Annotated as writing, not
   destructive. No tool edits, confirms or deletes a rule.
2. **A nested `answer` object** as in the proto. Closer to the wire, but
   one more level an agent gets wrong, for no extra power.

## Decision

A.1, B.1, C.1. Rules keep the order they were made in; moving one is
DEBT-0082.

## Consequences

- The owner sees every rule an agent made, and nothing it made reaches a
  device before a click.
- A rule meant to win over an earlier one is made by deleting and making
  them again until DEBT-0082 is paid.
