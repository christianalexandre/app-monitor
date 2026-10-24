---
id: ADR-0072
title: One no-op module per SDK module, checked against JVM, Android and klib dumps
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0072 — One no-op module per SDK module, checked against JVM, Android and klib dumps

## Context

PLAN-0005 M4.8. ADR-0062 C.1 (owner-accepted) replaces ADR-0026's single
`appmonitor-no-op` with `appmonitor-core-no-op`, `appmonitor-okhttp-no-op`
and `appmonitor-ktor-no-op`, the adapters' no-ops depending on the core one.
Not reopened here: the artifact names, the per-adapter split, and ADR-0026's
method (committed ABI dumps, a JVM test comparing them, defaults checked by
`OptionsDefaultsTest`). Left to this ADR: the Gradle module layout, how the
comparison covers the new iOS targets, and how the dry-run proves a no-op
pulls no real SDK.

Evidence, from this repository on 2026-10-24:

- `sdk/core` and `sdk/ktor` build `iosArm64` and `iosSimulatorArm64`
  (`appmonitor.kmp-sdk-ios`, ADR-0067); `updateKotlinAbi` writes one
  `<module>.klib.api` for both targets, beside the per-target JVM and
  Android `.api` files (`sdk/core/api/`).
- A klib dump names its library in a comment,
  `// Library unique name: <io.github.christianalexandre:core>`, and appends
  a `// <signature>` comment to every declaration; the no-op's dump differs
  on the first only (`sdk/core-no-op/api/core-no-op.klib.api`).
- KMP publications take their artifact id from the project name
  (`appmonitor.publish`): two projects named `core`, one nested under
  `sdk/no-op/`, would publish and link under the same klib unique name.
- `sdk/ktor` had no `appmonitor.publish` and no `POM_ARTIFACT_ID`, so its
  klib was named `app-monitor.sdk:ktor`, and it was not in the dry-run.

## Options

1. **Flat modules `sdk/core-no-op`, `sdk/okhttp-no-op`, `sdk/ktor-no-op`,
   each published; `sdk/no-op` becomes an unpublished JVM test project whose
   `ApiMatchTest` compares each pair on every dump the real module has,
   klib included with its comments dropped.** Unique project names, one
   comparison table, `./gradlew :sdk:no-op:check` keeps its meaning.
2. **Nested `sdk/no-op/{core,okhttp,ktor}`** — tidy tree, but the project
   names clash with `sdk/core` (above) unless each is renamed in
   `settings.gradle.kts`.
3. **A match test inside each no-op module** — no extra project, but the
   same parser three times.

## Decision

Option 1. `ApiMatchTest` lists the pairs (`core-no-op`/`core`,
`okhttp-no-op`/`android-okhttp`, `ktor-no-op`/`ktor`) with their dumps,
fails if either side ships a dump the table does not check, and compares a
klib dump's declarations and target list without its comments.
`sdk/ktor` joins `appmonitor.publish` as `appmonitor-ktor`. `make
publish-dry-run` checks every artifact and target, iOS included, and fails
if a no-op POM depends on an `appmonitor-*` artifact that is not a no-op.
`samples/android` links `sdk/okhttp-no-op` in release; its
`checkReleaseHasNoSdk` accepts only `-no-op` projects.

## Consequences

- An app swaps each adapter for its no-op: `appmonitor-okhttp` →
  `appmonitor-okhttp-no-op`, `appmonitor-ktor` → `appmonitor-ktor-no-op`;
  `appmonitor-core-no-op` comes transitively.
- A new SDK module with a public API needs its `-no-op` twin and a row in
  `ApiMatchTest`, or the "dump not checked" test fails.
- The klib comparison drops comments, so a change visible only in a
  signature comment would pass; the declaration line beside it carries the
  same types.
- DEBT-0026's dumper blind spot applies to the new pairs as it did before.
