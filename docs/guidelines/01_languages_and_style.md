# 01 — Languages and style

How code is written in each language of the repository.

## Rules

- **English everywhere** in the repository: identifiers, docs, comments,
  commits, PRs.
- **Kotlin**: `ktlint_official` style, enforced by ktlint through Spotless
  (`make lint`; fix with `./gradlew spotlessApply`). `explicitApi()` in every
  published SDK module.
- **Swift**: Swift API Design Guidelines, enforced by swift-format (added with
  the iOS SDK). Public SDK API is `public` on purpose; everything else is
  `internal` or narrower.
- **Public SDK API is small.** One entry point per SDK (`AppMonitor.start()`
  and options). Anything else public needs a reason in the PR.
- **Debug only.** SDK code must compile to nothing in release: every SDK ships a
  no-op artifact with the same public API. A public API change updates the
  dumps (`./gradlew updateKotlinAbi`) and the module's `sdk/*-no-op` twin in the same PR; on iOS,
  release builds exclude the whole SDK (ADR-0155), no `#if DEBUG` in the package,
  and the dump is rewritten by `scripts/swift_api_check.sh --update`.
- **No secrets in the repo.** Local values go in `local.properties` or env vars.
- **Formatting via `.editorconfig`**: 4 spaces, LF, final newline.

## Examples

| Do | Don't |
| --- | --- |
| `fun start(options: AppMonitorOptions = AppMonitorOptions())` | `fun start(host: String?, port: Int?, mask: Boolean, …)` |
| `internal class EnvelopeEncoder` | `class EnvelopeEncoder` in an SDK module |
