---
id: ADR-0165
title: Hello carries the app name the options set, and the desktop shows it before the app id
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-31
superseded_by:
---

# ADR-0165 — Hello carries the app name the options set

## Context

ADR-0017 B put `appName` in `AppMonitorOptions`, "shown in the desktop",
and ADR-0035 B mirrored it in the Swift SDK, but `Hello`
(`protocol/proto/appmonitor/v1/core.proto`) had no field for it: both SDKs
took the option and dropped it (DEBT-0024). PLAN-0015 H5.3 settles the wire
before M10 publishes the SDKs, because a field an old SDK does not send is
cheap now and a compatibility case later. The option, its type and its
default are unchanged: no public API moves.

## Options

1. **`string app_name = 11` on `Hello`** — proto3 scalar, empty when the app
   set none; a desktop reading an older SDK sees an empty string, an older
   desktop skips the unknown field. The sessions table already stores the
   whole `Hello` as a blob (SDD 09), so no migration.
2. **A column or an `apps.name` row on the desktop** — would let a later
   `Hello` rename the app for every stored session; nothing asks for that,
   and it needs a migration.
3. **Leave it out and drop the option** — removes a public option an app
   developer may already set: public API, out of this task.

## Decision

Option 1. Both SDKs send `AppMonitorOptions.appName`, or an empty string.
The desktop calls an app by `app_name`, else its `app_id`: in the approval
dialog's title (the `app_id` moves to its caption) and the devices pane.
Approvals, mocks and breakpoints stay keyed by `app_id`. The headless server
logs `[session] app name: <name>` on its own line, so the `connected:` line
the end to end scripts parse by position is unchanged.

## Consequences

- An app shows by the name its developer chose; two apps with one name
  are told apart by the `app_id` in the dialog's caption.
- The name is the one of the session's `Hello`: a later rename shows on the
  new sessions only.
- `make e2e-android`, `make e2e-ios` and `make e2e-kmp` assert every
  session of the sample carries its name (`assert_app_name`).
