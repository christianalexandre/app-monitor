---
id: PLAN-0004
title: Release gate
status: Active # Draft | Active | Done | Abandoned
milestone: M3
started: 2026-10-23
finished:
---

# PLAN-0004 — Release gate (M3)

## Goal

Nothing reaches `main` without proving the whole system still works:
`.github/workflows/release-gate.yml` runs on every PR to `main`, refuses any
head branch other than `dev`, and is green only when the desktop builds and
passes its tests on ubuntu, macOS and windows, the Kotlin ↔ Swift contract
tests pass, the Android and iOS samples reach a headless desktop server end
to end, the desktop packages and the SDK artifacts build; `main` requires
it if the plan allows.

## Tasks

M2's lessons applied: decisions in the "stop and ask" areas of `AGENTS.md`
come first, in one stop; every end to end runs locally as a `make` target
before it runs in the workflow, so a red gate can be reproduced on the Mac;
DEBTs whose trigger is in this milestone (DEBT-0002, DEBT-0040, DEBT-0041)
are paid or re-triggered by the task that fires them. The loop never merges
into `main`: the last task opens the release PR, the owner merges it.

- [x] **M3.1 Loop checks DEBT triggers.** (ADR-0051; `make debt-check`, matches backticked trigger names only) PLAN-0003's lesson: before
  committing, `/next` lists the open DEBTs whose trigger names a file the
  branch changes, and the PR body says how each was handled (paid,
  re-triggered, or not fired and why). `.claude/commands/next.md`, the PR
  template and, if scripted, `scripts/` with a bash test.
  Accept: `make scripts-test` (if scripted) and `make docs-check`; the PR of
  this task lists its own DEBT check.
- [x] **M3.2 Release gate decisions.** (`docs/release-gate-decisions`; ADR-0052 to ADR-0055 `Accepted` by the owner: the repository goes public instead of paying for runners, every id under `io.github.christianalexandre`, packages unsigned at M10 too; M3.2a added) `Proposed` ADRs, one stop:
  (a) cost — runner minutes per gate run (macOS 10×, windows 2× on a private
  repo, ADR-0012), measured or estimated per job, and which jobs run on
  which runner; (b) branch protection or a ruleset on `main` requiring the
  gate — whether the account's GitHub plan offers it for a private repo,
  and what replaces it if not; (c) SDK publication coordinates — Maven
  group and artifact ids of `sdk/*`, the SwiftPM package and product names
  an app adds and the tag scheme, which an app developer types;
  (d) packaging — unsigned dry-run packages only until M10, the bundle id
  and package names.
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [ ] **M3.2a Public repository.** (added by M3.2, ADR-0052) A `LICENSE`
  with the license the owner picks, the README's license line, and the
  repository made public by the owner. No gate job runs on a GitHub-hosted
  runner before this.
  Accept: `gh repo view --json visibility` prints `PUBLIC` and `gh repo view
  --json licenseInfo` names the license.
- [ ] **M3.3 Gate skeleton and desktop matrix.** `release-gate.yml` on
  `pull_request` to `main` plus `workflow_dispatch`; a first job fails when
  the head branch is not `dev`; desktop build and tests
  (`:desktop:app:test`, `:desktop:server:test`, `:protocol:jvmTest`) on
  ubuntu, macOS and windows, one job per check (ADR-0052). `pr-conventions.yml` and
  `ci.yml` unchanged on PRs to `dev`.
  Accept: a `gh workflow run release-gate.yml --ref <branch>` run green on
  the three OSes, linked in the PR; a run with a non-`dev` head fails at the
  branch job.
- [ ] **M3.4 Contract tests Kotlin ↔ Swift in the gate.** A macOS job runs
  `make swift` (fixtures decode and re-encode in Swift, proto freshness,
  public API dump, simulator tests) and `./gradlew :protocol:allTests` (iOS
  simulator target included). Fixes what only shows on a clean runner.
  Accept: the gate run green with the job's log showing the Swift fixture
  tests and `iosSimulatorArm64Test` executed, not skipped.
- [ ] **M3.5 Android end to end, locally.** A `make e2e-android` target:
  headless server (`make server`'s entry point) on a free port, sample debug
  APK installed on a booted emulator, every sample call triggered without a
  hand (a launch extra, as the iOS `-runAll YES`), the server log asserted
  for each expected envelope (request, response, failure, WebSocket
  frames). Device approval as the headless server does it. DEBT-0002's
  trigger fires here: pay it or re-trigger it.
  Accept: `make e2e-android` green on the Mac against an emulator; a sample
  call removed from the run makes it fail, shown in the PR.
- [ ] **M3.6 Android end to end in the gate.** A job that boots an emulator
  on the runner M3.2 (a) picks and runs `make e2e-android`; server log and
  logcat uploaded on failure.
  Accept: the gate run green with the job's assertion lines in its log.
- [ ] **M3.7 iOS end to end, locally.** A `make e2e-ios` target: headless
  server, `make ios-sample`'s Debug app on a booted simulator launched with
  `-runAll YES`, the server log asserted as in M3.5 (URLSession calls,
  WebSocket, background download). DEBT-0041's trigger is the first release
  PR: re-trigger it or state the gap in the PR.
  Accept: `make e2e-ios` green on the Mac; a sample call removed from the
  run makes it fail, shown in the PR.
- [ ] **M3.8 iOS end to end in the gate.** The macOS job (or a second one,
  per M3.2 (a)) runs `make e2e-ios`; server log and simulator log uploaded
  on failure.
  Accept: the gate run green with the job's assertion lines in its log.
- [ ] **M3.9 Packaging dry-run.** Compose Desktop `nativeDistributions` for
  `desktop/app` with the names M3.2 (d) decides; the gate builds `.dmg` on
  macOS, `.msi` on windows and `.deb` on ubuntu, unsigned, and uploads them
  as short-lived artifacts. SDD 02 says how to build one locally.
  Accept: `./gradlew :desktop:app:packageDmg` on the Mac produces a `.dmg`
  that launches the app; the gate run lists the three artifacts.
- [ ] **M3.10 SDK publication dry-run.** `sdk/core`, `sdk/android-okhttp`
  and `sdk/no-op` publish with the coordinates of M3.2 (c) to a local Maven
  repository, POMs complete; the iOS package is resolved by a throwaway
  SwiftPM project from a path or tag and built in Debug and Release.
  DEBT-0040's trigger fires here: pay it (release build without
  SwiftProtobuf) or re-trigger it with what the dry-run measured.
  Accept: `./gradlew publishAllPublicationsToDryRunRepository` (or the
  plugin's equivalent) and the SwiftPM consumer build in the gate, green;
  the published file list in the PR.
- [ ] **M3.11 Gate required on `main`.** Branch protection or a ruleset as
  M3.2 (b) decides, set with `gh api` and recorded (the JSON in the repo, or
  the command in SDD 02); otherwise a DEBT with a trigger.
  Accept: `gh api repos/{owner}/{repo}/rules/branches/main` (or the
  protection endpoint) lists the gate's checks as required, or the DEBT.
- [ ] **M3.12 Docs sync and first release PR.** SDD 01/02 describe the gate,
  `AGENTS.md` lists the new commands, README status updated; a draft PR
  `dev` → `main` opened so the gate runs for real. The loop does not merge
  it.
  Accept: the release PR's gate green on every job, linked in the plan's
  *Outcome*.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
