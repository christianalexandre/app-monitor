---
id: PLAN-0011
title: Hardening before the field test
status: Active # Draft | Active | Done | Abandoned
milestone: H1
started: 2026-10-25
finished:
---

# PLAN-0011 — Hardening before the field test (H1)

## Goal

What a developer meets when adding the SDKs to a real app, rather than a
sample, works: setup, approval, capture in OkHttp, Ktor and `URLSession`,
and the Gradle plugin. The open DEBTs that a real app hits on that path
are paid. Asked by the owner before the roadmap's Hold (field test), so
that the field test finds the app's problems, not known ones.

## Tasks

No new feature. Each DEBT task pays the DEBT and fills its "How it was
paid". H1.1's findings that normal use hits become `H1.R*` tasks here,
at most two; the rest go into one DEBT (ADR-0094). Non-correctness review
DEBTs (DEBT-0049 … DEBT-0088 cleanups) stay out.

- [ ] **H1.1 Review the field-test path.** `code-review` at `medium` over
  what a real app runs, not one milestone's diff: the setup steps of the
  README and SDD 02 (dependency, Gradle block or plugin, Run Script,
  start), discovery and approval, the transport, and the capture in
  `sdk/android-okhttp`, `sdk/ktor`, `sdk/ios` and `gradle-plugin/`.
  Findings committed in `docs/plans/` as `H1.R*` tasks or one DEBT.
  Accept: the findings list in the PR, each with its failure scenario,
  triaged by the ADR-0094 bar.
- [ ] **H1.2 Ktor on OkHttp is captured once.** DEBT-0087: the Ktor plugin
  marks its request on Android too, and `AppMonitorInterceptor` skips a
  marked request.
  Accept: `./gradlew :sdk:ktor:allTests :sdk:android-okhttp:allTests` — a
  Ktor client on the OkHttp engine with both SDKs installed sends one
  exchange per call; the mark never reaches the server.
- [ ] **H1.3 A held iOS task asks the app's delegate.** DEBT-0085:
  `HoldProtocol` hands authentication challenges and redirects to the
  app's session delegate.
  Accept: `swift test --package-path sdk/ios` — a held task whose
  delegate rejects a challenge fails as without the breakpoint, and a
  redirect the delegate refuses is not followed.
- [ ] **H1.4 Background tasks from before `start`.** DEBT-0039: a
  background transfer resumed before `AppMonitor.start`, or in an earlier
  launch, is captured when it completes, with its start time marked as
  unknown rather than invented.
  Accept: `make swift-sim` — a background task resumed before `start`
  reaches the stand-in desktop at completion.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
