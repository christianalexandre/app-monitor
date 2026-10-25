---
id: PLAN-0010
title: Interaction
status: Active # Draft | Active | Done | Abandoned
milestone: M9
started: 2026-10-25
finished:
---

# PLAN-0010 — Interaction (M9)

## Goal

The desktop changes what a debug app's network calls do, not only shows
them. A mock rule answers a matching call on the device without the
network. A breakpoint holds a call until the developer resumes it,
edited or not. A stored exchange can be sent again. Mocks and replay
work in every SDK that captures full HTTP: OkHttp, Ktor and
`URLSession`. The Gradle plugin of ADR-0093 adds the OkHttp interceptor
to a debug app without a line of code.

## Tasks

M8's lesson applied: a value one writer produces and another reads back
is tested with what the other writer really sends. Here the writer is
the desktop and the readers are three SDKs. The rules and commands come
from one fixture vector (`protocol/fixtures/`), and every SDK runs it, as
it runs `masking.json` today. M4's kept: decisions in the "stop and ask"
areas come first, in one stop. Library primitives (the Ktor call built
by hand, `URLProtocol`, AGP instrumentation) are written down with their
evidence before the code. M8's split rule kept: a task whose second half
needs its own decision is split as soon as that shows.

- [x] **M9.1 Interaction decisions.** (ADR-0110, ADR-0111, ADR-0112
  accepted with every recommended option) One stop:
  (a) mocks: where a rule is matched, what it matches and answers, which
  captures serve it, how a mocked exchange shows, who creates rules
  (window, MCP under ADR-0005's confirmation), the SDK's public API;
  (b) breakpoints and replay: what pauses, what can be edited, what
  happens on timeout and disconnect, masked values on resume, where a
  replay is sent from;
  (c) the Gradle plugin's OkHttp injection: what it rewrites, which
  variants, duplicates with a hand-written line, WebSockets, opt-out,
  and where the plugin lives before M10.
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [x] **M9.2 Mock rules on the wire and in the store.** (`feat/interaction-channel`; split from the plan's M9.2, whose window and MCP half is M9.3; ADR-0113: one whole-set `MockRules`, the rule as a blob, a set past one frame cut; migration `3.sqm`; DEBT-0068's `published` item paid) The `interaction`
  channel per ADR-0110 in `protocol` (messages, fixtures, the Swift
  samples mirrored). The desktop stores rules per app (a migration) and
  sends them to an approved device that lists the channel, at `HelloAck`
  and on every change. New SDD chapter for the channel; SDD 09 updated.
  Accept: `./gradlew :protocol:allTests :desktop:storage:test :desktop:server:test :desktop:app:test`.
  A stored rule reaches a stand-in device as one envelope, after
  `HelloAck`, and again on a change. A pending rule is not sent.
- [x] **M9.3 Mock rules in the window and MCP.** (`feat/mock-editor`; ADR-0114: a Mocks panel counting rules to confirm, "Mock this" without framing and encoding headers, flat `create_mock` arguments; DEBT-0082 rule order) The window gets a mock
  editor, opened from an exchange or empty, that marks rules left out of
  the frame (ADR-0113 C.1). The MCP tool creates rules under ADR-0005's
  confirmation. SDD 10 and 12 updated.
  Accept: `./gradlew :desktop:mcp:test :desktop:app:test`. A rule made in
  the window reaches a stand-in device as one envelope. An MCP rule stays
  inactive until confirmed. Offscreen screenshot `mocks.png`.
- [x] **M9.4 Mocks in the Kotlin SDKs.** (`feat/kotlin-sdk-mocks`; split: the desktop's mark and the end to end are M9.5; ADR-0115: rules in the session's `MonitorClient`, matched in `sdk/core` from the URL string, Ktor answered at `Monitoring` with `finish()`; DEBT-0083 Ktor internal API) `sdk/core` keeps the rules it
  receives. `AppMonitorInterceptor` and `AppMonitorKtor` answer a
  matching call without the network. The exchange carries its rule. A
  shared matching vector in `protocol/fixtures/`. SDD 04, 05 and 08
  updated.
  Accept: `./gradlew :sdk:core:allTests :sdk:android-okhttp:allTests :sdk:ktor:allTests`.
- [x] **M9.5 Mocked exchanges in the desktop, end to end.** (`feat/mocked-exchanges`; split from
  M9.4; ADR-0116: column `mock_rule_id` (migration `4.sqm`), a deleted rule shown by id, `--mocks FILE` on the headless server; the Android sample's `418, or a mock` button) The desktop stores `RequestStarted.mock_rule_id` (a migration)
  and the window and `get_request` show an exchange as mocked, with its
  rule's name. The headless server (`make server`) can be given rules
  to push, so an end to end asserts one. SDD 09, 10 and 12 updated.
  Accept: `./gradlew :desktop:storage:test :desktop:app:test :desktop:mcp:test`
  and `make e2e-android`, with one rule asserted end to end.
- [x] **M9.6 Mocks in the iOS SDK.** (`feat/ios-sdk-mocks`; ADR-0117: `MockProtocol` put first by a replaced `protocolClasses` getter, the rule chosen at resume, events from the existing CFNetwork hooks, which ADR-0110 expected silent; the iOS sample's `418, or a mock` button) `sdk/ios` per ADR-0110: the same
  vector, a matching task answered without the network, background
  sessions left as they are. SDD 06 and 07 updated.
  Accept: `make swift`, `make swift-sim` and `make e2e-ios`, with one rule
  asserted end to end.
- [ ] **M9.7 Breakpoints and replay.** Per ADR-0111, in every SDK of M9.4
  and M9.6 and in the window. Pause, edit, resume, abort and timeout. A
  replay is started from an exchange's detail, or from MCP under
  confirmation.
  Accept: each SDK's tests above, which hold a call, resume it edited and
  let one time out. `./gradlew :desktop:app:test` with an offscreen
  screenshot `breakpoint.png`. A replayed exchange is stored and linked
  to its original.
- [ ] **M9.8 OkHttp injection by the Gradle plugin.** The plugin module
  of ADR-0093, injection only (the release swap stays M10), per ADR-0112.
  Gradle TestKit tests on a consumer project. The Android sample drops
  its hand-written interceptor line. README setup and SDD 05 updated.
  Accept: the plugin's TestKit tests, `make android-samples`, and
  `make e2e-android` with the sample's line removed.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
