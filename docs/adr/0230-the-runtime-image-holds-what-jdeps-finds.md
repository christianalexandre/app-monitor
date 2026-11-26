---
id: ADR-0230
title: The desktop's runtime image holds every module jdeps finds, checked by make check
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0230 — The desktop's runtime image holds every module jdeps finds, checked by make check

## Context

The `1.0.0` packages do not start, on any OS. On Windows the launcher shows *Failed to launch
JVM*. Reproduced on macOS with the JDK 21 limited to the package's modules (its runtime's
`release` file lists them) and the installed app's jars:

```
Exception in thread "main" java.lang.NoClassDefFoundError: java/net/http/HttpClient
	at appmonitor.desktop.app.store.GitHubReleases.<init>(GitHubReleases.kt:26)
	at appmonitor.desktop.app.MainKt.main(Main.kt:108)
```

ADR-0228 added `GitHubReleases`, built on the JDK's `java.net.http`. `modules(...)` in
`desktop/app/build.gradle.kts` is a fixed list, and nothing compared it with what the jars
use. Every test runs on a full JDK, so all of them passed. `./gradlew
:desktop:app:suggestRuntimeModules` (jdeps over the app's jars) prints `java.naming`,
`java.net.http` and `jdk.jfr` beyond the list; the first two releases started without
`java.naming` and `jdk.jfr` because nothing on their start path loads them.

## Options

1. **Add the three modules, and fail `make check` when jdeps finds one the list lacks.**
   About 15 s on a warm build.
2. **Add only `java.net.http`.** The smallest image, but the next JDK API in a dependency
   breaks a release the same way.
3. **Ship the full JDK runtime.** No list to keep, a much larger download.

## Decision

Option 1. `scripts/runtime_modules_check.sh` reads Compose's suggestion and fails on a
module `modules(...)` lacks; `make check` runs it as `runtime-modules-check`. `jdk.crypto.ec`
stays listed by hand: jdeps cannot see it (ADR-0105).

## Consequences

- A dependency or a JDK call needing a new module fails the PR, not the release.
- The check sees only what jdeps sees: reflection and service loading stay invisible, as
  `jdk.crypto.ec` was. A package that is never launched before it ships still can fail
  some other way (DEBT-0139).
- The fix ships in `1.0.1`; `1.0.0`'s packages stay broken where they are installed.
