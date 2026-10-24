# app-monitor — instructions for agents

Single source of context for any coding assistant. `CLAUDE.md` is a symlink to
this file — edit **this** one.

## What this project is

A network monitor and debug toolkit for mobile apps. SDKs embedded in **iOS**
(native Swift), **Android** and **Kotlin Multiplatform** apps stream what the
app does (HTTP, WebSocket, gRPC, later logs, analytics, storage…) to a
**Compose Multiplatform desktop app** (macOS, Linux, Windows) on the same
network. The desktop stores everything in SQLite and exposes a local **MCP
server** so AI agents can query traffic, runs, issues and benchmarks.

It is a solo portfolio project: quality and clarity of decisions matter as much
as features.

## The documentation

| Where | What | Nature |
| --- | --- | --- |
| `docs/sdd/` | What the system **is** and how to work on it: architecture, code map, how to run. | Descriptive. Current state. |
| `docs/guidelines/` | **How to write** it: style, naming, git flow, comments, tests. | Prescriptive. |
| `docs/adr/` | Why a decision was taken. One decision per file. | Historical. Immutable once accepted. |
| `docs/tech-debt/` | What is known to be missing, **with a trigger**. | Backlog of conscious debt. |
| `docs/plans/` | One plan per milestone: its tasks while active, its outcome once done. Roadmap in the README. | Loop state while active; history once done. |

**Read `docs/sdd/` and `docs/guidelines/` before writing code.** The rule that
separates them: **if it describes how things are, it is SDD; if it tells you
what to do, it is guidelines.**

## Rules that are not broken

- **Evidence before conclusion.** A test, a log, a protocol dump, a doc link —
  never model memory. Say where the evidence came from.
- **Say what you actually ran.** `make check` green is not verified code. In
  every PR and every iteration report, list what ran and what did not.
- **Design changed → `docs/sdd/` changes in the same PR.** The docs are the
  agent's memory across sessions; stale docs poison every later iteration.
- **No silent exception to a written rule.** Either it is a bug, or it is a new
  ADR that changes the rule.
- **Something left undone?** It becomes a `docs/tech-debt/` item **with a
  trigger** — an event that forces payment, not an intention.

## Decisions: what the agent may decide alone

The agent **decides and records** (writes an ADR with `status: Accepted`) any
technical decision inside the agreed architecture: libraries, module layout,
internal APIs, test strategy. The owner reviews it in the PR.

The agent **stops and asks** (ADR with `status: Proposed`, then ends the
iteration) only when the decision touches:

1. **Product scope** — a feature not in a plan or in the roadmap
   (`docs/plans/`), or dropping one.
2. **Public API of an SDK** — anything an app developer would have to change.
3. **Licensing / cost** — a dependency that is not MIT, Apache-2.0, BSD or
   similar, or that costs money.
4. **Security / privacy** — what is captured, masked, stored or exposed.

## The loop

`/next` runs one iteration: pick the next task of the active plan in
`docs/plans/`, implement it on a semantic branch, run the gates, update docs,
commit, open the PR to `dev`.
`make loop` repeats it, each iteration in a **fresh session** (the docs are the
memory) inside a dedicated worktree (`../app-monitor-loop`), so the main
checkout stays free; `/loop /next` repeats it inside one session. Stop conditions are in
`.claude/commands/next.md`. Everything the loop writes — reports included —
is in English.

## Code comments

A comment answers **what breaks if someone touches this**. The *why* lives in
an ADR or in the SDD; in source, at most a reference like `// ADR-0004` or
`// DEBT-NNNN`.

- **At most 4 lines per contiguous block**, delimiters included.
- **Cite as `ADR-NNNN` or `DEBT-NNNN`**, nothing else, and only ids that exist.
- No history ("renamed because…"), no paraphrase of the ADR you just cited.

Full rule: `docs/guidelines/02_comments.md`.

## Git

- `main` = released. `dev` = integration (default branch). Everything leaves
  from `dev` and comes back to `dev` through a PR; `main` only receives
  `dev` through a release PR.
- Branches: `<type>/<kebab-slug>`, types from Conventional Commits.
- Commits and PR titles: Conventional Commits. Squash merge.
- **Never** add `Co-Authored-By` or "Generated with" lines. Commits are the
  owner's.

Full rule: `docs/guidelines/03_git_and_pull_requests.md`.

## Commands

| What | Command | State |
| --- | --- | --- |
| List make targets | `make help` | active; default goal |
| All gates | `make check` | active: `docs-check` + `lint` + `scripts-test` + `build` + `swift` |
| Docs consistency | `make docs-check` | active |
| Kotlin lint (ktlint via Spotless) | `make lint` | active; fix with `./gradlew spotlessApply` |
| Gradle build and tests | `make build` | active |
| Protocol tests, all targets | `./gradlew :protocol:allTests` | active; JVM, Android host, iOS simulator |
| SDK core tests (buffer, backoff, transport, discovery) | `./gradlew :sdk:core:allTests` | active; JVM and Android host; transport against an in-process desktop, discovery with fake browsers |
| OkHttp capture tests (interceptor + WebSocket factory + masking vector) | `./gradlew :sdk:android-okhttp:allTests` | active; JVM against `mockwebserver3` and `protocol/fixtures/masking.json`, Android host has no tests |
| No-op SDK check (API matches the real SDK, defaults) | `./gradlew :sdk:no-op:check` | active; compares `sdk/*/api/` dumps, ADR-0026 |
| Update SDK public API dumps | `./gradlew updateKotlinAbi` | active; after a deliberate public API change, then align `sdk/no-op` |
| Desktop server tests (WebSocket + mDNS) | `./gradlew :desktop:server:test` | active; needs a multicast-capable LAN interface |
| Regenerate protocol fixtures | `./gradlew :protocol:jvmTest -PupdateFixtures` | active; only for a deliberate contract change; mirror the change in `sdk/ios/Tests/AppMonitorTests/Samples.swift` |
| iOS SDK gates (proto freshness, swift-format, `swift test`, debug/release public API, Info.plist keys script, simulator tests, sample build) | `make swift` | active; macOS with `swift` only, prints `swift: SKIPPED` elsewhere, `swift-sim: SKIPPED` and `ios-sample: SKIPPED` without XcodeGen; ADR-0037, ADR-0039, ADR-0047, ADR-0048, ADR-0049, ADR-0050 |
| iOS SDK public API, debug and release builds against `sdk/ios/api/AppMonitor.swiftinterface` | `scripts/swift_api_check.sh` | active; macOS with `swift`; `--update` rewrites the dump after a deliberate public API change; ADR-0048 |
| iOS SDK tests hosted in an app on the simulator (background sessions), then the local network keys in the Debug `TestHost.app` only | `make swift-sim` | active; Xcode and `brew install xcodegen`; first available iPhone simulator, or `SIM_DEVICE="iPhone 17"`; log in `build/ios-simulator.log`; ADR-0047, ADR-0049 |
| iOS sample in Debug and Release for the simulator, keys and SDK names in Debug only | `make ios-sample` | active; Xcode and `brew install xcodegen`; `SIM_DEVICE` as `make swift-sim`; app in `build/ios-sample/Build/Products/`, log in `build/ios-sample.log`; ADR-0050 |
| Run the iOS sample against a desktop, every button in turn | `xcrun simctl install booted build/ios-sample/Build/Products/Debug-iphonesimulator/Sample.app` then `xcrun simctl launch booted dev.appmonitor.sample -runAll YES` | active; after `make ios-sample`; `make server` or `make desktop` running; SDD 02 |
| Info.plist keys script tests (which configurations, app's keys kept, idempotent) | `scripts/test_add_local_network_keys.sh` | active; macOS (`plutil`), in `make swift`; ADR-0049 |
| iOS SDK tests (fixtures decode and re-encode, transport against an in-process desktop, discovery with fake browsers and one real Bonjour round, `URLSession` capture against an in-process HTTP server and WebSocket echo, masking vector) | `swift test --package-path sdk/ios` | active; Swift Testing on the macOS host; the Bonjour round needs a multicast-capable interface |
| iOS transport against a running desktop | `APPMONITOR_LIVE_HOST=127.0.0.1 swift test --package-path sdk/ios --filter LiveServerTests` | active; `make server` or `make desktop` running; skipped without the variable |
| iOS Bonjour discovery against a running desktop | `APPMONITOR_LIVE_DISCOVERY=1 swift test --package-path sdk/ios --filter LiveServerTests` | active; `make server` or `make desktop` running on the LAN; skipped without the variable |
| Regenerate the iOS SDK protobuf code | `make swift-proto` | active; after any `protocol/proto` change; builds `protoc` from swift-protobuf (~1 min cold) |
| Desktop app (Compose), listens for devices | `make desktop` | active; port 47474 |
| Desktop app tests (store, body formats, offscreen UI) | `./gradlew :desktop:app:test` | active; screenshots in `desktop/app/build/screenshots/` (`app.png`, `approval.png`, `frames.png`) |
| End to end screenshots from a real device or emulator | `./gradlew :desktop:app:liveScreenshots -PliveName=device` | active; real server on 47474, approves the first device, writes `docs/screenshots/<name>-*.png`; `-PliveAdvertise=false` forces the emulator's `10.0.2.2`; drive the sample by hand, SDD 02, ADR-0031 |
| Headless desktop server, logs envelopes to stdout | `make server` | active; `ARGS="--port N"` |
| Replay fixture traffic to a desktop | `make fake-traffic` | active; mDNS discovery, or `ARGS="--host H [--port N]"`; `--name S` picks a service, `--timeout MS` bounds discovery |
| Fake client tests (replay + mDNS discovery) | `./gradlew :tools:fake-client:test` | active; needs a multicast-capable LAN interface |
| Android sample APKs (debug with the SDK, release with `sdk/no-op`) | `./gradlew :samples:android:assembleDebug :samples:android:assembleRelease` | active; `:samples:android:checkReleaseHasNoSdk` in `check`, ADR-0030 |
| Sample host tests (WebSocket echo listener) | `./gradlew :samples:android:testDebugUnitTest` | active; JVM, no device |
| Run the sample on an emulator | `adb install -r samples/android/build/outputs/apk/debug/android-debug.apk` | active; allow the local network prompt on Android 17+; `make server` or `make desktop` running |
| Release gate on any ref (branch job, desktop tests on ubuntu, macOS, windows) | `gh workflow run release-gate.yml --ref <branch> [-f head=<name>]` | active; also on every PR to `main`; `head` (default `dev`) is the branch checked as the PR head; ADR-0052, DEBT-0046 |
| Loop script tests (bash) | `make scripts-test` | active; `scripts/test_loop.sh` and `scripts/test_debt_check.sh`, ADR-0013 |
| Branch name check | `make branch-check` | active |
| Open DEBTs whose trigger names a file the branch changes | `make debt-check` | active; `BASE=<ref>` (default `origin/dev`), committed and uncommitted changes, always exits 0; `/next` self-check, ADR-0051 |
| Development loop, fresh session per iteration | `make loop` | active; worktree `../app-monitor-loop`, logs in `.loop/`; env `MAX_ITERATIONS`, `MAX_BUDGET_USD`, `ITERATION_TIMEOUT`, `LIMIT_RETRIES`, `PLAN_CHECKPOINT=1` (stop at each plan's close, ADR-0019) |
| Follow the running loop live | `make loop-watch` | active |
| Create the loop worktree | `make loop-worktree` | active; honours `LOOP_WORKTREE`; open `claude` there once to accept the trust dialog |

Commands are added here **in the same PR** that creates them.

## Language

Everything in the repository — code, docs, comments, commits, PRs — is in
**English**. Conversation with the owner may be in Portuguese.
