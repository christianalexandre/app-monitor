---
id: ADR-0136
title: Desktop view models are wired by a Koin DSL module per window
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0136 — Desktop view models are wired by a Koin DSL module per window

## Context

ADR-0133 chose Koin (Apache-2.0) for injection and JetBrains' multiplatform
`lifecycle-viewmodel-compose` (Apache-2.0) for view models. PLAN-0012's
H2.2b brings both, with the first feature module, `desktop/feature/traffic`.
Three things are left open:

- **How definitions are declared.** mibo-smart-app declares them with
  annotations: `feature/home/.../HomeModule.kt` is a `@Module
  @Configuration @ComponentScan` class, and `HomeViewModel` is a
  `@KoinViewModel`, read by the Koin compiler plugin
  (`io.insert-koin.compiler.plugin` 1.2.1 beside Koin 4.2.2 in its
  `gradle/libs.versions.toml`). The compiler plugin is a Kotlin compiler
  plugin, tied to the compiler version; this build is on Kotlin 2.4.21.
  nowinandroid uses Hilt, which is Android only.
- **Where the Koin context lives.** Most of `desktop/app`'s 68 tests compose
  `AppMonitorApp`, each over its own store, in one JVM. A global
  `startKoin` (or Koin's `KoinApplication` composable, which starts the
  global context and throws `KoinApplicationAlreadyStartedException` on a
  second start, per its KDoc in `koin-compose` 4.2.2) would leak one test's
  store into the next.
- **Who owns the view models.** `koinViewModel()` reads
  `LocalViewModelStoreOwner` and fails when none is provided
  (`koin-compose-viewmodel` 4.2.2, `ViewModel.kt`); the offscreen test host
  is not a window, and one owner must serve both.

Versions from Maven Central's `maven-metadata.xml` on 2026-10-25: Koin
4.2.2 and `org.jetbrains.androidx.lifecycle` 2.11.0, the same as
mibo-smart-app's catalog.

## Options

1. **Annotations and the Koin compiler plugin**, as mibo-smart-app. No
   definition list; one more compiler plugin to keep in step with Kotlin.
2. **The Koin DSL** (`module { viewModel { … } }`), one `Module` value per
   feature, included by the app's module. Same library and runtime as
   mibo-smart-app, written by hand; a constructor change is a one-line
   edit in the module.
3. **No container**: each screen builds its view model with
   `viewModel { … }`. Nothing to wire, but every repository travels down
   the composable tree, which ADR-0133 chose Koin to avoid.

## Decision

Option 2, following mibo-smart-app's split (a module per feature, the
shell wires them, `koinViewModel()` in the screen) without its compiler
plugin.

- Each feature exposes one `Module` (`trafficModule`) that declares its
  view models over the domain's repository interfaces; a repository that
  may be missing is read with `getOrNull()`.
- `desktop/app`'s `appModule(...)` includes them and binds the
  repositories of the open store (`di/AppModule.kt`).
- `AppMonitorApp` builds an isolated context with `koinApplication { }`
  and provides it with `KoinIsolatedContext`, so each window, and each
  test, has its own; it closes the context when it leaves the
  composition.
- `AppMonitorApp` provides its own `ViewModelStoreOwner` and clears it
  when it leaves the composition, so a view model lives exactly as long
  as the window that shows it, in the app and in tests alike.
- A view model's state is one `StateFlow` of an immutable data class,
  read with `collectAsState()`; tests set `Dispatchers.Main` to a test
  dispatcher.

## Consequences

- A new view model is one line in its feature's module; a missing
  definition fails when the screen opens, not at compile time, which the
  app's UI tests cover.
- No global Koin state: the UI tests keep composing the app over their
  own stores.
- Moving to annotations later only changes how modules are declared; the
  screens' `koinViewModel()` stays.
