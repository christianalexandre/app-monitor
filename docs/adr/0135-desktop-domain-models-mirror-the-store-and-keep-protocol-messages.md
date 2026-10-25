---
id: ADR-0135
title: Desktop domain models mirror the store's and keep the protocol's messages
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0135 — Desktop domain models mirror the store's and keep the protocol's messages

## Context

ADR-0133 puts a pure Kotlin `desktop/domain` between the screens and
`desktop/storage`, with `desktop/data` mapping the store's types to the
domain's. PLAN-0012's H2.2 starts it with the traffic panes: runs,
sessions, the request list and one request's detail. Three things are
open:

- **What a domain model holds.** The store's rows (`StoredRun`,
  `StoredSession`, `StoredExchange`, `ExchangeDetail`, `StoredFrame`)
  carry Wire messages from `:protocol`: a session's `Hello`, a request's
  `Header`s and bodies as `okio.ByteString`, `CaptureLayer` and
  `WebSocketFrame` enums. Re-declaring them in the domain would copy the
  device contract a second time.
- **How a repository is reached** before H2.2b brings Koin (ADR-0133).
- **Where the mapping lives** while screens of H2.3 and H2.4 still take
  the store's types from the same shell.

AGENTS.md's standing decision: desktop architecture follows
`nowinandroid` or `mibo-smart-app`. Read for this ADR:

- nowinandroid `core/data/.../repository/OfflineFirstTopicsRepository.kt`:
  an `internal` implementation of the `TopicsRepository` interface maps
  each row with `TopicEntity::asExternalModel`, declared next to the
  entity in `core/database/.../model/TopicEntity.kt`; the external model
  `Topic` lives in `core/model`.
- mibo-smart-app `docs/guidelines/09_camada_de_dados.md`: the core model
  reaches a feature only through its `data/` layer, by an `internal`,
  pure mapper with a unit test; `<Name>RepositoryImpl` is `internal`.

## Options

1. **Domain types for everything, the protocol's included.** No `:protocol`
   in `desktop/domain`; every header, body and enum mapped. The strictest;
   most of the code is a copy of `.proto` fields, and ADR-0081 already
   says the desktop serves traffic as the device sent it.
2. **Domain types for the store's rows, the protocol's messages kept.**
   `Run`, `Session`, `Exchange`, `ExchangeFilter`, `StatusClass`,
   `HttpExchange` (with `CapturedBody`, `CapturedFrame`,
   `ConnectionBytes`) and `StoreHealth` in `desktop/domain`; their fields
   use `Hello`, `Header`, `CaptureLayer` and `WebSocketFrame` as is.
3. **The store's types in the domain** (`desktop/domain` depends on
   `desktop/storage`). No mapping; a screen sees SQLDelight's module on
   its classpath, which ADR-0133 rules out.

## Decision

Option 2, as nowinandroid does with `core/model`, the device's messages
standing in for what its `core/network` models are.

- `desktop/domain` depends on `:protocol` and coroutines only.
- `desktop/data` implements `RunRepository` and `TrafficRepository` with
  `internal` classes over `Storage`, mapping each row with a `toDomain()`
  in `Mappers.kt` (nowinandroid's `asExternalModel`); the repositories
  are reached by `Storage.runRepository()` and
  `Storage.trafficRepository()` until H2.2b's Koin module replaces them.
- `toDomain()` of `StoredRun` and `StoredExchange`, and
  `ExchangeFilter.toStored()`, are public while the issues, benchmarks,
  run diff and export screens still read the store from `desktop/app`;
  H2.4 makes them `internal` again.

## Consequences

- A protocol change reaches the screens without a domain change; a new
  column in the store needs one in the domain and its mapper.
- The detail model `HttpExchange` moved from `desktop/app` to
  `desktop/domain`; `Mocks.kt` reads it from there.
- `desktop/data`'s public mappers are a temporary leak of the store's
  types into its API; H2.4's Accept covers removing them.
