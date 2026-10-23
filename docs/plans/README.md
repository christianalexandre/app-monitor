# Plans

One plan per milestone: the tasks `/next` consumes while it is active, and the
record of how the milestone was actually built once it is done.

## Rules

- **Next number**: the highest in this folder + 1, four digits. Copy
  `_template.md` to `NNNN-kebab-slug.md`.
- **Status**: `Draft` (being written) → `Active` (the loop works on it) →
  `Done` or `Abandoned`. **Only one plan is `Active`** at a time.
- Task status: `[ ]` todo · `[~]` in progress (`branch`) · `[x]` done
  (`#PR`) · `[!]` blocked (reason, ADR or DEBT id).
- Every task has an **Accept** line: a command or observable result that
  proves it. A task is not `[x]` until that proof is in its PR.
- While `Active`, the agent may split, reorder or add tasks. Adding or dropping
  a **feature** is product scope: ask the owner.
- **Closing**: fill *Outcome*, set `status` and `finished`. From then on the
  plan is **immutable** — it is the historical record. Follow-up work goes in
  the next plan.
- **Next plan**: written from the roadmap below in a `docs/` PR, before any
  code of that milestone. The roadmap row then links to it.
- **Checkpoint**: when every task is `[x]`, the agent reviews the milestone
  (`code-review`) in at most three rounds — the first on the whole milestone
  diff, the next ones only on what changed since — and turns correctness
  findings into `MN.R*` tasks, the rest into one DEBT per round (ADR-0016).
  Then it opens the close-plan PR and merges it after green CI, unless a
  correctness finding is left open or `PLAN_CHECKPOINT=1` is set: then it
  stops, and the owner merging that PR is the approval to start the next
  plan (ADR-0019).

## Index

| Plan | Milestone | Title | Status |
| --- | --- | --- | --- |
| [0001](0001-foundation.md) | M0 | Foundation | Done |
| [0002](0002-android-sdk.md) | M1 | Android SDK | Done |

## Roadmap

Milestones not yet planned. Scope here is a starting point, not a contract —
the plan sharpens it.

### M2 — iOS SDK (capture layer 1)

A real iOS app (simulator and device) shows all its URLSession traffic. Swift
package `sdk/ios` (Network.framework transport, NWBrowser, swift-protobuf),
URLSession capture ported from `app-network-monitor` without Pulse, sessions
created before `start()`, uploads, WebSocket tasks, background sessions
(ADR-0004 layer 1). Contract tests against `protocol/fixtures/`. SwiftUI sample
app. Info.plist build-phase script. Protocol field telling which capture
layer produced an entry (ADR-0004 requires the UI to show it).

### M3 — Release gate

Nothing reaches `main` without proving the whole system still works.
`.github/workflows/release-gate.yml` on PRs to `main` (only from `dev`):

- desktop build and tests on ubuntu, macOS and windows;
- protocol contract tests Kotlin ↔ Swift;
- Android e2e: sample app on an emulator talking to a headless desktop server;
- iOS e2e: sample app on a simulator talking to a headless desktop server;
- packaging dry-run (`.dmg`, `.msi`, `.deb`) and SDK publication dry-run.

Branch protection/rulesets on `main` requiring the gate, if the plan allows
(otherwise a DEBT with trigger). macOS runners cost 10× minutes on private
repos: iOS and macOS jobs run only in the release gate, not on every PR.

### M4 — KMP SDK

`sdk/ktor` HttpClient plugin (all targets), correlation header and dedupe with
the Swift SDK (ADR-0003), KMP sample app.

### M5 — Persistence and runs

`desktop/storage` with SQLDelight (devices, apps, runs, requests, bodies
compressed), named runs, search and filters, retention settings.

### M6 — MCP server

`desktop/mcp` on `127.0.0.1` (ADR-0005), tools `list_runs`, `search_requests`,
`get_request`, `endpoint_stats`, `diff_runs`, `export_har`, `to_curl`; setup
docs for Claude Code.

### M7 — Issues, benchmarks, export

Issues (requests + note + tags + status, also via MCP `save_issue` /
`list_issues`), per-endpoint p50/p95/p99, error rate, bytes, run-to-run diff,
HAR and cURL export in the UI.

### M8 — iOS deep capture (layers 2–4)

grpc-swift interceptor adapter, connection metadata via symbol rebinding,
desktop MITM proxy mode with HTTP/2 + gRPC decoding and simulator CA install
(ADR-0004). First, identify with Instruments which SDK the old tool missed.

### M9 — Interaction

Mocks and breakpoints (desktop → device), replay, Gradle plugin that injects
the OkHttp interceptor automatically.

### M10 — Distribution

Signed desktop packages via GitHub Releases, Homebrew cask, Maven Central,
SwiftPM tags, docs site, README with GIFs.

### Later — more debug channels

Logs, analytics events, app storage (DB, preferences), deeplinks, performance.
Each one is a channel (ADR-0001) and a `desktop/feature-*` module. Product
scope: the owner picks the order.
