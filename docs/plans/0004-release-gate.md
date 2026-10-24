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
- [x] **M3.2a Public repository.** (`LICENSE` MIT, chosen by the owner, in `chore/mit-license` #120; VPN and LAN addresses in DEBT-0036 replaced by examples; the owner made the repository public, `gh repo view` prints `PUBLIC` and `MIT License`) (added by M3.2, ADR-0052) A `LICENSE`
  with the license the owner picks, the README's license line, and the
  repository made public by the owner. No gate job runs on a GitHub-hosted
  runner before this.
  Accept: `gh repo view --json visibility` prints `PUBLIC` and `gh repo view
  --json licenseInfo` names the license.
- [x] **M3.3 Gate skeleton and desktop matrix.** (`ci/release-gate-skeleton`; branch job reuses `scripts/branch_check.sh`, so `release/x.y.z` passes too as guidelines 03 allow; dispatch takes a `head` input; mDNS round trip skipped on hosted macOS, DEBT-0046; measured ≈3.5 macOS, ≈4 ubuntu, ≈4.6 windows min) `release-gate.yml` on
  `pull_request` to `main` plus `workflow_dispatch`; a first job fails when
  the head branch is not `dev`; desktop build and tests
  (`:desktop:app:test`, `:desktop:server:test`, `:protocol:jvmTest`) on
  ubuntu, macOS and windows, one job per check (ADR-0052). `pr-conventions.yml` and
  `ci.yml` unchanged on PRs to `dev`.
  Accept: a `gh workflow run release-gate.yml --ref <branch>` run green on
  the three OSes, linked in the PR; a run with a non-`dev` head fails at the
  branch job.
- [x] **M3.4 Contract tests Kotlin ↔ Swift in the gate.** (`ci/release-gate-contract`; one `contract` job on macos-latest, Xcode 26.6, XcodeGen from Homebrew; nothing needed fixing on a clean runner; measured ≈5.7 min against ADR-0052's ≈15 estimate; the Swift Bonjour round passes on the runner, noted in DEBT-0046) A macOS job runs
  `make swift` (fixtures decode and re-encode in Swift, proto freshness,
  public API dump, simulator tests) and `./gradlew :protocol:allTests` (iOS
  simulator target included). Fixes what only shows on a clean runner.
  Accept: the gate run green with the job's log showing the Swift fixture
  tests and `iosSimulatorArm64Test` executed, not skipped.
- [x] **M3.5 Android end to end, locally.** (`test/android-end-to-end`; ADR-0056: `runAll`, `host`, `port` launch extras, `scripts/e2e_android.sh`; DEBT-0002 re-triggered, `make check` 66 s; DEBT-0047 public services; API 37 grant path not run, the 37.2 image reports SDK 36) A `make e2e-android` target:
  headless server (`make server`'s entry point) on a free port, sample debug
  APK installed on a booted emulator, every sample call triggered without a
  hand (a launch extra, as the iOS `-runAll YES`), the server log asserted
  for each expected envelope (request, response, failure, WebSocket
  frames). Device approval as the headless server does it. DEBT-0002's
  trigger fires here: pay it or re-trigger it.
  Accept: `make e2e-android` green on the Mac against an emulator; a sample
  call removed from the run makes it fail, shown in the PR.
- [x] **M3.6 Android end to end in the gate.** (`ci/release-gate-e2e-android`; `e2e-android` job on ubuntu-latest with KVM, `reactivecircus/android-emulator-runner@v2`, API 36 `google_apis` x86_64; run 40049064470 green, measured ≈3 min against ADR-0052's ≈10 estimate; DEBT-0047 did not fire) A job that boots an emulator
  on the runner M3.2 (a) picks and runs `make e2e-android`; server log and
  logcat uploaded on failure.
  Accept: the gate run green with the job's assertion lines in its log.
- [x] **M3.7 iOS end to end, locally.** (`test/ios-end-to-end`; ADR-0057: `-host`, `-port` launch arguments, `scripts/e2e_ios.sh` boots a simulator when none is; assertions moved to `scripts/e2e_lib.sh`, `make e2e-android` rerun green on an API 36 emulator; DEBT-0047 widened to iOS; DEBT-0041 not fired, it fires at the release PR) A `make e2e-ios` target: headless
  server, `make ios-sample`'s Debug app on a booted simulator launched with
  `-runAll YES`, the server log asserted as in M3.5 (URLSession calls,
  WebSocket, background download). DEBT-0041's trigger is the first release
  PR: re-trigger it or state the gap in the PR.
  Accept: `make e2e-ios` green on the Mac; a sample call removed from the
  run makes it fail, shown in the PR.
- [x] **M3.8 iOS end to end in the gate.** (`ci/release-gate-e2e-ios`; separate `e2e-ios` job on macos-latest, ADR-0052 Option 1; run 40051258417 green, measured ≈14 min against ≈10 estimated; loopback host, DEBT-0046 not fired; the run's first `e2e-android` attempt failed on a corrupt emulator download, green on rerun) The macOS job (or a second one,
  per M3.2 (a)) runs `make e2e-ios`; server log and simulator log uploaded
  on failure.
  Accept: the gate run green with the job's assertion lines in its log.
- [x] **M3.9 Packaging dry-run.** (`ci/release-gate-packaging`; `package` job ×3 OS, run 40053289077 green with `desktop-dmg`, `desktop-msi`, `desktop-deb` artifacts; measured ≈5.7 macOS, ≈3.7 windows, ≈3.6 ubuntu min against ≈5 each; runtime modules from `suggestRuntimeModules`; no `.deb` maintainer e-mail, a public package would expose it) Compose Desktop `nativeDistributions` for
  `desktop/app` with the names M3.2 (d) decides; the gate builds `.dmg` on
  macOS, `.msi` on windows and `.deb` on ubuntu, unsigned, and uploads them
  as short-lived artifacts. SDD 02 says how to build one locally.
  Accept: `./gradlew :desktop:app:packageDmg` on the Mac produces a `.dmg`
  that launches the app; the gate run lists the three artifacts.
- [x] **M3.10 SDK publication dry-run.** (`build/sdk-publication-dry-run`; ADR-0058: `maven-publish`, `protocol` published as `appmonitor-protocol` since `sdk/core` depends on it, root `Package.swift`, `make publish-dry-run`, `publish` job on macOS; DEBT-0040 re-triggered to M10's first tag, release consumer 3.6 MB with 14,767 SwiftProtobuf symbols; run 40055218483, `publish` ≈6.3 min) `sdk/core`, `sdk/android-okhttp`
  and `sdk/no-op` publish with the coordinates of M3.2 (c) to a local Maven
  repository, POMs complete; the iOS package is resolved by a throwaway
  SwiftPM project from a path or tag and built in Debug and Release.
  DEBT-0040's trigger fires here: pay it (release build without
  SwiftProtobuf) or re-trigger it with what the dry-run measured.
  Accept: `./gradlew publishAllPublicationsToDryRunRepository` (or the
  plugin's equivalent) and the SwiftPM consumer build in the gate, green;
  the published file list in the PR.
- [x] **M3.11 Gate required on `main`.** (`ci/release-gate-required`; `protect-main` ruleset updated with the 11 gate checks, JSON in `.github/rulesets/protect-main.json`; probe PR #130 from a non-`dev` branch `BLOCKED`, closed unmerged; DEBT-0048 check names not matched to jobs) Branch protection or a ruleset as
  M3.2 (b) decides, set with `gh api` and recorded (the JSON in the repo, or
  the command in SDD 02); otherwise a DEBT with a trigger.
  Accept: `gh api repos/{owner}/{repo}/rules/branches/main` (or the
  protection endpoint) lists the gate's checks as required, or the DEBT.
- [x] **M3.12 Docs sync and first release PR.** (`docs/release-gate-docs`; SDD 01 *Verification*, README status; AGENTS.md already listed every command; draft release PR #132 opened, not merged; DEBT-0041 fired and re-triggered to M10's first tag, no iPhone) SDD 01/02 describe the gate,
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
