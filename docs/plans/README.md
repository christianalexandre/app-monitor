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

## Roadmap

Milestones not yet planned. Scope here is a starting point, not a contract —
the plan sharpens it.

### Hold — field test before M10

The owner runs the SDKs in a real app (not only the samples), fixes what
breaks and improves the desktop's interface together with the agent
before anything is published. The loop does not plan past this section:
reaching it is **stop: waiting for the owner**, who removes it when the
field test is done.

### M10 — Distribution

Unsigned desktop packages via GitHub Releases and a Homebrew tap of the
owner's, with the steps to let them run (ADR-0055: no signing cost), Maven
Central, SwiftPM tags, docs site, README with GIFs. The Gradle plugin that
swaps the SDK for its no-ops in release builds, published with the SDK
(ADR-0093); its TestKit consumer then resolves the published
`appmonitor-okhttp` too, the module names of ADR-0126 A.1.

### Later — more debug channels

Logs, analytics events, app storage (DB, preferences), deeplinks, performance.
Each one is a channel (ADR-0001) and a `desktop/feature-*` module. Product
scope: the owner picks the order.
