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

Exception (ADR-0094): a public API or security / privacy decision whose
every recommended option follows a **standing owner decision** below is
`Accepted` by the agent, citing it. Product scope and licensing / cost
always stop. Only the owner adds to this list:

- **Debug only.** Release builds hold no SDK code (ADR-0072, ADR-0073,
  ADR-0093).
- **Little masking.** No masking rule beyond today's without a concrete
  case; `masking = false` turns all off (ADR-0034). The desktop stores and
  serves traffic as the device sent it (ADR-0081, ADR-0089).
- **Local exposure only.** MCP on loopback with `Host`/`Origin` checks and a
  token (ADR-0090); devices on the LAN only after approval (ADR-0083).
- **Short setup.** A few copy-paste steps, each documented: dependency,
  Gradle block, Run Script, import, start. An option that removes or
  shortens a step qualifies; one that adds a step does not.

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
| Fast suite, what PRs to `dev` run | `make check` | active: `docs-check` + `lint` + `scripts-test` + `build` (sample apps excluded) + `swift` (host only); ADR-0076 |
| Fast suite plus simulator tests and sample apps (end to ends separate) | `make check-full` | active; `make check` + `make swift-sim` + `make samples`; simulator parts macOS with Xcode and XcodeGen; ADR-0076 |
| Every sample app, debug and release, release checks | `make samples` | active; `android-samples` (`./gradlew :samples:android:build`) + `ios-sample` + `kmp-sample`; release gate `samples` job; ADR-0076 |
| Docs consistency | `make docs-check` | active |
| Kotlin lint (ktlint via Spotless) | `make lint` | active; fix with `./gradlew spotlessApply` |
| Gradle build and tests | `make build` | active |
| Protocol tests, all targets | `./gradlew :protocol:allTests` | active; JVM, Android host, iOS simulator |
| SDK core tests (buffer, backoff, transport, discovery) | `./gradlew :sdk:core:allTests` | active; JVM, Android host and iOS simulator (macOS with Xcode); transport against a stand-in desktop, and the real one on JVM, discovery with fake browsers and one real Bonjour round on the simulator (multicast-capable interface); ADR-0066, ADR-0067, ADR-0068 |
| SDK core on the iOS simulator against a running desktop | `APPMONITOR_LIVE_HOST=127.0.0.1 ./gradlew :sdk:core:iosSimulatorArm64Test --tests '*LiveServerTest*'` | active; `make server` or `make desktop` running; skipped without the variable; ADR-0067 |
| SDK core on the iOS simulator finding a running desktop by Bonjour | `APPMONITOR_LIVE_DISCOVERY=1 ./gradlew :sdk:core:iosSimulatorArm64Test --tests '*LiveServerTest*'` | active; `make server` or `make desktop` running on the LAN; skipped without the variable; connects to the first desktop answered; ADR-0068 |
| OkHttp capture tests (interceptor + WebSocket factory + masking vector) | `./gradlew :sdk:android-okhttp:allTests` | active; JVM against `mockwebserver3` and `protocol/fixtures/masking.json`, Android host has no tests |
| Ktor capture tests (plugin against `MockEngine` + masking vector + capture header + WebSocket frames against an in-process echo server) | `./gradlew :sdk:ktor:allTests` | active; JVM, Android host and iOS simulator (macOS with Xcode); header on iOS only, ADR-0071; frames through CIO everywhere, OkHttp on JVM, Darwin on the simulator; ADR-0069, ADR-0070 |
| No-op SDK check (each `sdk/*-no-op` API matches its real module, klib included, defaults) | `./gradlew :sdk:no-op:check` | active; compares `sdk/*/api/` dumps, ADR-0072 |
| Update SDK public API dumps | `./gradlew updateKotlinAbi` | active; after a deliberate public API change, then align the module's `sdk/*-no-op` twin |
| Desktop server tests (WebSocket + mDNS) | `./gradlew :desktop:server:test` | active; needs a multicast-capable LAN interface |
| Regenerate protocol fixtures | `./gradlew :protocol:jvmTest -PupdateFixtures` | active; only for a deliberate contract change; mirror the change in `sdk/ios/Tests/AppMonitorTests/Samples.swift` |
| iOS SDK host gates (proto freshness, swift-format, `swift test`, debug/release public API, Info.plist keys script) | `make swift` | active; in `make check`; macOS with `swift` only, prints `swift: SKIPPED` elsewhere; simulator tests and samples moved to `make swift-sim` and `make samples`; ADR-0037, ADR-0039, ADR-0048, ADR-0049, ADR-0076 |
| iOS SDK public API, debug and release builds against `sdk/ios/api/AppMonitor.swiftinterface` and `AppMonitorGRPC.swiftinterface` | `scripts/swift_api_check.sh` | active; macOS with `swift`; `--update` rewrites both dumps after a deliberate public API change; ADR-0048, ADR-0099 |
| iOS SDK tests hosted in an app on the simulator (background sessions, the connection layer's raw sockets), then the local network keys in the Debug `TestHost.app` only | `make swift-sim` | active; Xcode and `brew install xcodegen`; first available iPhone simulator, or `SIM_DEVICE="iPhone 17"`; log in `build/ios-simulator.log`; ADR-0047, ADR-0049 |
| iOS sample in Debug and Release for the simulator, keys and SDK names in Debug only | `make ios-sample` | active; Xcode and `brew install xcodegen`; `SIM_DEVICE` as `make swift-sim`; app in `build/ios-sample/Build/Products/`, log in `build/ios-sample.log`; ADR-0050 |
| Run the iOS sample against a desktop, every button in turn | `xcrun simctl install booted build/ios-sample/Build/Products/Debug-iphonesimulator/Sample.app` then `xcrun simctl launch booted dev.appmonitor.sample -runAll YES` | active; after `make ios-sample`; `make server` or `make desktop` running; SDD 02 |
| KMP sample, Android and iOS, debug and release, SDK names in debug only | `make kmp-sample` | active; in `make samples`; iOS part macOS with Xcode and `brew install xcodegen`, `SIM_DEVICE` as `make swift-sim`; apps in `samples/kmp/androidApp/build/outputs/apk/` and `build/kmp-sample/Build/Products/`, log in `build/kmp-sample.log`; ADR-0073 |
| Run the KMP sample against a desktop, every button in turn | Android: `adb install -r samples/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk` then `adb shell am start -S -W -n appmonitor.sample.kmp/.MainActivity --es host 10.0.2.2 --ez runAll true`; iOS: `xcrun simctl install booted build/kmp-sample/Build/Products/Debug-iphonesimulator/KmpSample.app` then `xcrun simctl launch booted dev.appmonitor.kmpsample -host 127.0.0.1 -runAll YES` | active; after `make kmp-sample`; `make server` or `make desktop` running; SDD 02 |
| Info.plist keys script tests (which configurations, app's keys kept, idempotent) | `scripts/test_add_local_network_keys.sh` | active; macOS (`plutil`), in `make swift`; ADR-0049 |
| iOS SDK tests (fixtures decode and re-encode, transport against an in-process desktop, discovery with fake browsers and one real Bonjour round, `URLSession` capture against an in-process HTTP server and WebSocket echo, tasks with `sdk/ktor`'s capture header skipped, masking vector, the gRPC adapter against an in-process grpc-swift server, the connection layer against raw BSD sockets) | `swift test --package-path sdk/ios` | active; Swift Testing on the macOS host; the Bonjour round needs a multicast-capable interface |
| iOS transport against a running desktop | `APPMONITOR_LIVE_HOST=127.0.0.1 swift test --package-path sdk/ios --filter LiveServerTests` | active; `make server` or `make desktop` running; skipped without the variable |
| iOS Bonjour discovery against a running desktop | `APPMONITOR_LIVE_DISCOVERY=1 swift test --package-path sdk/ios --filter LiveServerTests` | active; `make server` or `make desktop` running on the LAN; skipped without the variable |
| Regenerate the iOS SDK protobuf code | `make swift-proto` | active; after any `protocol/proto` change; builds `protoc` from swift-protobuf (~1 min cold) |
| Desktop app (Compose), listens for devices, serves MCP | `make desktop` | active; devices on port 47474, MCP on `127.0.0.1:47475/mcp` (`APPMONITOR_MCP_PORT=<n>` moves it, the token and command in Settings, SDD 10, ADR-0090); proxy on `127.0.0.1:47476` once switched on in Settings (`APPMONITOR_PROXY_PORT=<n>` moves it, SDD 11); history in the data directory, `APPMONITOR_DATA_DIR=<dir>` moves it (SDD 09, ADR-0085) |
| Every MCP tool against a running desktop | `npx @modelcontextprotocol/inspector --cli http://127.0.0.1:47475/mcp --header "Authorization: Bearer <token>" --method tools/call --tool-name <tool>` | active; `make desktop` running, token from the settings' command or `sqlite3 <data dir>/appmonitor.db "select value from settings where key='mcp.token'"`; Node 22.19+; Claude Code setup in the README, scripted check in SDD 02; ADR-0091 |
| Unsigned desktop package for the host OS | `./gradlew :desktop:app:packageDmg` (`packageMsi`, `packageDeb`) | active; output in `desktop/app/build/compose/binaries/main/`; SDD 02, ADR-0055 |
| Desktop storage tests (open, ingest, write queue, paging, approvals, runs, search, retention, issues, data directory) and migration verification | `./gradlew :desktop:storage:test :desktop:storage:verifySqlDelightMigration` | active; JVM, in `make build`; temporary data directories; SDD 09, ADR-0085 |
| Regenerate the storage schema dump after a new migration | `./gradlew :desktop:storage:generateMainAppMonitorDatabaseSchema` | active; writes the next `desktop/storage/src/main/sqldelight/databases/<version>.db`, commit it; a shipped one is never edited; ADR-0085 |
| Desktop MCP server tests (SDK client over HTTP, access checks, lifecycle, tools) | `./gradlew :desktop:mcp:test` | active; JVM, in `make build`; servers on `127.0.0.1` port 0, stores in temporary directories; `ToCurlTest` runs `sh` and `curl`; SDD 10, ADR-0092 |
| Desktop proxy tests (curl through it with and without the CA, plain HTTP, HTTP/2, grpc-java unary, streaming and error calls, refused origins, port taken, loopback only, CA kept and renewed, lifecycle) | `./gradlew :desktop:proxy:test` | active; JVM, in `make build`; runs `curl` (with HTTP/2); proxy, Netty and grpc-java origins on `127.0.0.1` port 0, store in a temporary directory; SDD 11, ADR-0105, ADR-0106 |
| Desktop app tests (store on a temporary data directory, body formats, MCP and proxy wiring, offscreen UI) | `./gradlew :desktop:app:test` | active; screenshots in `desktop/app/build/screenshots/` (`app.png`, `approval.png`, `benchmarks.png`, `connections.png`, `diff.png`, `export.png`, `filters.png`, `frames.png`, `issues.png`, `mcp-port-taken.png`, `proxy.png`, `reopen.png`, `runs.png`, `settings.png`); `TrafficStoreTest` prints the 10k-request time, `SearchTest` in `desktop/storage` the unindexed search time, `ApprovalStoreTest` restarts the store three times against a real server; SDD 09, ADR-0083 |
| End to end screenshots from a real device or emulator | `./gradlew :desktop:app:liveScreenshots -PliveName=device` | active; real server on 47474, approves the first device, writes `docs/screenshots/<name>-*.png`, the last after reopening the store; `-PliveDataDir=<dir>` keeps the store for the packaged app (SDD 02); `-PliveAdvertise=false` forces the emulator's `10.0.2.2`; drive the sample by hand, SDD 02, ADR-0031 |
| Headless desktop server, logs envelopes to stdout | `make server` | active; `ARGS="--port N"` |
| Replay fixture traffic to a desktop | `make fake-traffic` | active; mDNS discovery, or `ARGS="--host H [--port N]"`; `--name S` picks a service, `--timeout MS` bounds discovery |
| Local end to end target tests (httpbin.org paths, WebSocket echo) | `./gradlew :tools:e2e-target:test` | active; started by `make e2e-android` and `make e2e-ios` on `127.0.0.1`, port 0; ADR-0074 |
| Fake client tests (replay + mDNS discovery) | `./gradlew :tools:fake-client:test` | active; needs a multicast-capable LAN interface |
| Android sample: build, lint, host tests, release holds no SDK | `make android-samples` | active; `./gradlew :samples:android:build`, in `make samples`; ADR-0030, ADR-0076 |
| Android sample APKs (debug with the SDK, release with `sdk/okhttp-no-op`) | `./gradlew :samples:android:assembleDebug :samples:android:assembleRelease` | active; `:samples:android:checkReleaseHasNoSdk` in `make samples` (Gradle `check`), ADR-0030, ADR-0076 |
| Android end to end on a booted emulator (headless server on a free port, sample with `runAll`, every envelope asserted) | `make e2e-android` | active; `ANDROID_SERIAL` picks the emulator, `E2E_TIMEOUT` (s, 120); calls `tools/e2e-target`, not the internet; logs in `build/e2e-android/`; SDD 02, ADR-0056, ADR-0074 |
| iOS end to end on a simulator (headless server on a free port, Debug sample with `-host`, `-port`, `-runAll`, every envelope asserted) | `make e2e-ios` | active; macOS with Xcode and XcodeGen; booted iPhone simulator, else boots one (`SIM_DEVICE` picks by name), `E2E_TIMEOUT` (s, 120); calls `tools/e2e-target`, not the internet; logs in `build/e2e-ios/`; SDD 02, ADR-0057, ADR-0074 |
| KMP end to end, Android then iOS (KMP sample's debug app with `host`, `port`, `target`, `runAll`, every envelope asserted once; on iOS beside the Swift SDK, two sessions asserted) | `make e2e-kmp` | active; `make e2e-kmp-android` (booted emulator, `ANDROID_SERIAL`) or `make e2e-kmp-ios` (macOS with Xcode and XcodeGen, `SIM_DEVICE`) for one; `E2E_TIMEOUT` (s, 120); logs in `build/e2e-kmp-android/` and `build/e2e-kmp-ios/`; SDD 02, ADR-0065, ADR-0075 |
| SDK publication dry-run (Kotlin SDKs and their no-ops to `build/maven-dry-run`, POMs checked, no-ops free of real SDK artifacts, SwiftPM consumer of the root `Package.swift` in debug and release, with and without `AppMonitorGRPC`) | `make publish-dry-run` | active; Swift part macOS only, `publish-swift: SKIPPED` elsewhere; prints the file list and DEBT-0040's numbers; ADR-0058 |
| Sample host tests (WebSocket echo listener) | `./gradlew :samples:android:testDebugUnitTest` | active; JVM, no device |
| Run the sample on an emulator | `adb install -r samples/android/build/outputs/apk/debug/android-debug.apk` | active; allow the local network prompt on Android 17+; `make server` or `make desktop` running |
| Release gate on any ref (branch job, desktop tests on ubuntu, macOS, windows) | `gh workflow run release-gate.yml --ref <branch> [-f head=<name>]` | active; also on every PR to `main`; `head` (default `dev`) is the branch checked as the PR head; ADR-0052, DEBT-0046 |
| Apply the `dev` ruleset (`conventions`, `docs`, `check` required) after editing it | `gh api -X PUT repos/christianalexandre/app-monitor/rulesets/25604902 --input .github/rulesets/protect-dev.json` | active; check with `gh api repos/christianalexandre/app-monitor/rules/branches/dev`; auto-merge on in the repository settings; ADR-0094 |
| Apply the `main` ruleset (gate required) after editing it | `gh api -X PUT repos/christianalexandre/app-monitor/rulesets/25604903 --input .github/rulesets/protect-main.json` | active; check with `gh api repos/christianalexandre/app-monitor/rules/branches/main`; ADR-0053, DEBT-0048 |
| Loop and end to end script tests (bash) | `make scripts-test` | active; `scripts/test_loop.sh`, `scripts/test_debt_check.sh`, `scripts/test_sim_list.sh` (simulator list parser) and `scripts/test_e2e_lib.sh` (end to end assertions on a crafted log), ADR-0013, ADR-0075 |
| Branch name check | `make branch-check` | active |
| Open DEBTs whose trigger names a file the branch changes | `make debt-check` | active; `BASE=<ref>` (default `origin/dev`), committed and uncommitted changes, exits 0 unless the base does not resolve; `/next` self-check, ADR-0051, ADR-0061 |
| Development loop, fresh session per iteration | `make loop` | active; worktree `../app-monitor-loop`, logs in `.loop/`; env `MAX_ITERATIONS` (50), `MAX_BUDGET_USD`, `ITERATION_TIMEOUT`, `LIMIT_RETRIES`, `PLAN_CHECKPOINT=1` (stop at each plan's close, ADR-0019); PRs merge on auto, ADR-0094 |
| Follow the running loop live | `make loop-watch` | active |
| Create the loop worktree | `make loop-worktree` | active; honours `LOOP_WORKTREE`; open `claude` there once to accept the trust dialog |

Commands are added here **in the same PR** that creates them.

## Language

Everything in the repository — code, docs, comments, commits, PRs — is in
**English**. Conversation with the owner may be in Portuguese.
