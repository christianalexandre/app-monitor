# 01 — Languages and style

How code is written in each language of the repository.

## Rules

- **English everywhere** in the repository: identifiers, docs, comments,
  commits, PRs.
- **Kotlin**: official Kotlin style, enforced by ktlint (added with the first
  Kotlin module). `explicitApi()` in every published SDK module.
- **Swift**: Swift API Design Guidelines, enforced by swift-format (added with
  the iOS SDK). Public SDK API is `public` on purpose; everything else is
  `internal` or narrower.
- **Public SDK API is small.** One entry point per SDK (`AppMonitor.start()`
  and options). Anything else public needs a reason in the PR.
- **Debug only.** SDK code must compile to nothing in release: every SDK ships a
  no-op artifact with the same public API.
- **No secrets in the repo.** Local values go in `local.properties` or env vars.
- **Formatting via `.editorconfig`**: 4 spaces, LF, final newline.

## Examples

| Do | Don't |
| --- | --- |
| `fun start(options: AppMonitorOptions = AppMonitorOptions())` | `fun start(host: String?, port: Int?, mask: Boolean, …)` |
| `internal class EnvelopeEncoder` | `class EnvelopeEncoder` in an SDK module |
