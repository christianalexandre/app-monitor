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
  code of that milestone. The roadmap row then links to it. A plan the
  owner wrote ahead is `Draft` and its roadmap section links to it: it is
  activated as written, not rewritten.
- **Size**: four to six tasks. No standalone "design on paper" or "docs
  sync" task: a design lands with the first task that needs it, and every
  PR keeps the docs it touches current (ADR-0094).
- **Checkpoint**: when every task is `[x]`, the agent reviews the milestone
  (`code-review` at `medium`) **once**, on the whole milestone diff. Only
  findings that normal use hits become `MN.R*` tasks; the rest go into one
  DEBT (ADR-0094). The findings ride in the first `MN.R*` PR, and the close
  in the last one (or in a close-plan PR when there is no task). It merges on
  auto unless a correctness finding is left open or `PLAN_CHECKPOINT=1` is
  set: then it stops, and the owner merging that PR is the approval to start
  the next plan (ADR-0019).

## Index

| Plan | Milestone | Title | Status |
| --- | --- | --- | --- |
| [0001](0001-foundation.md) | M0 | Foundation | Done |
| [0002](0002-android-sdk.md) | M1 | Android SDK | Done |
| [0003](0003-ios-sdk.md) | M2 | iOS SDK | Done |
| [0004](0004-release-gate.md) | M3 | Release gate | Done |
| [0005](0005-kmp-sdk.md) | M4 | KMP SDK | Done |
| [0006](0006-persistence-and-runs.md) | M5 | Persistence and runs | Done |
| [0007](0007-mcp-server.md) | M6 | MCP server | Done |
| [0008](0008-issues-benchmarks-export.md) | M7 | Issues, benchmarks, export | Done |
| [0009](0009-ios-deep-capture.md) | M8 | iOS deep capture | Done |
| [0010](0010-interaction.md) | M9 | Interaction | Done |
| [0011](0011-field-test-hardening.md) | H1 | Hardening before the field test | Done |
| [0012](0012-desktop-architecture.md) | H2 | Desktop clean architecture and design system | Done |
| [0013](0013-desktop-finesse.md) | H3 | Desktop finesse — live traffic, recordings, themes and layout | Done |
| [0014](0014-plug-and-play-setup.md) | H4 | Plug and play setup | Done |
| [0015](0015-release-readiness.md) | H5 | Release readiness — what publishing makes expensive | Done |
| [0016](0016-debt-sweep.md) | H6 | Debt sweep before distribution | Done |
| [0017](0017-cleanup-and-gzip-bodies.md) | H7 | The sweep's last findings and gzip bodies | Done |
| [0018](0018-dense-traffic-window.md) | H8 | A dense traffic window, as the owner's mockup | Done |
| [0019](0019-publish-the-libraries.md) | M10 | Publish the libraries — Maven Central and SwiftPM, one version | Done |
| [0020](0020-field-test-desktop-fixes.md) | H9 | What the 0.1.0 field test asks of the desktop | Done |
| [0021](0021-desktop-distribution-and-1-0-0.md) | M11 | Desktop distribution and 1.0.0 | Done |
| [0022](0022-the-front-page.md) | M12 | The repository's front page | Active |

## Roadmap

Milestones not yet planned. Scope here is a starting point, not a contract —
the plan sharpens it.

### Later — an inspector inside the app, after `1.0.0`

The owner's ask (2026-11-26), for after the `1.0.0` of the libraries and of the
desktop, not before: like app-network-monitor's shake that opened PulseUI, a
shake (or a gesture on the simulator and emulator) opens a simple screen from
the SDK inside the debug app. It lists the requests, shows their details, and
offers to **forget the desktop** the device is paired with (ADR-0168), so a
developer moves to another desktop without clearing the app's data.

Questions for the owner when it is planned, each in a "stop and ask" area:
product scope (what the screen shows); the SDKs' public API (how a developer
turns it on, Android, iOS and KMP); security / privacy (requests kept on the
device, and an unpin on the device, which reopens ADR-0168); licensing (own
screens, or Pulse on iOS, MIT, and Chucker on Android, Apache-2.0). Adding
API is compatible, so it fits a `1.x` minor.

### Later — readable bodies

A body the window cannot read today (owner, 2026-10-30, after Firebase's
`batchlog` in the field test): a gzip `Content-Encoding` opens since
[PLAN-0017](0017-cleanup-and-gzip-bodies.md); next protobuf decoded without
a schema (field numbers and values), later with `.proto` files the developer
adds. Product scope: the owner picks when.

### Later — more debug channels

Logs, analytics events, app storage (DB, preferences), deeplinks, performance.
Each one is a channel (ADR-0001) and a `desktop/feature-*` module. Product
scope: the owner picks the order.
