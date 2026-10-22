# ADR — Architecture Decision Records

One decision per file: context, decision, consequences.

Rules:

- **Next number**: the highest in this folder + 1, four digits. Copy
  `_template.md` to `NNNN-kebab-slug.md`.
- **Status**: `Proposed` (needs the owner — see "Decisions" in `AGENTS.md`),
  `Accepted`, `Superseded` (with `superseded_by`), `Rejected`.
- **Accepted ADRs are immutable.** Changed your mind? Write a new ADR that
  supersedes it, and set the old one's `status: Superseded` and
  `superseded_by` (the only edit allowed).
- An accepted ADR is reflected in `sdd/` or `guidelines/` **in the same PR**.
- Add the row to the index below.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-protobuf-envelope-protocol.md) | Protobuf protocol with a channel envelope | Accepted |
| [0002](0002-desktop-server-device-client.md) | Desktop is the server; devices discover and connect | Accepted |
| [0003](0003-native-swift-ios-sdk.md) | Native Swift iOS SDK, no Kotlin runtime | Accepted |
| [0004](0004-layered-ios-capture.md) | Layered capture coverage on iOS | Accepted |
| [0005](0005-desktop-storage-and-mcp.md) | SQLite storage and in-process MCP server | Accepted |
| [0006](0006-gradle-build-and-lint.md) | Gradle build with convention plugins, Spotless and ktlint | Accepted |
| [0007](0007-android-kmp-target.md) | Android target through the Android KMP library plugin | Accepted |
| [0008](0008-protocol-schema-and-fixtures.md) | Protocol schema layout and golden fixtures | Accepted |
| [0009](0009-desktop-server-sessions-and-mdns.md) | Desktop server sessions, endpoint and mDNS advertisement | Accepted |
| [0010](0010-fake-client-and-headless-server.md) | Fake client replays golden fixtures; desktop server runs headless | Accepted |
| [0011](0011-desktop-app-skeleton.md) | Desktop app skeleton with Material, snapshot store and offscreen UI test | Accepted |
| [0012](0012-pr-ci-workflow.md) | PR CI runs make check on ubuntu with the open-source Gradle cache | Accepted |
| [0013](0013-loop-scripts-bash-tests.md) | Loop scripts are tested by a plain bash test in make check | Accepted |
| [0014](0014-loop-watchdog-polls-a-marker.md) | Loop watchdog polls a done marker instead of trapping TERM | Accepted |
| [0015](0015-loop-timeout-by-exit-status.md) | Loop reports a timeout only when the watchdog's TERM killed the session | Accepted |
| [0016](0016-bounded-milestone-review.md) | Milestone review is incremental, keeps only correctness findings and stops after three rounds | Accepted |
| [0017](0017-android-sdk-public-api.md) | Android SDK public API and minSdk | Accepted |
| [0018](0018-capture-masking-and-device-approval.md) | What the Android SDK captures and masks, and device approval | Accepted |
| [0019](0019-loop-merges-the-close-plan-pr.md) | The loop merges the close-plan PR and moves on to the next plan | Accepted |
| [0020](0020-body-truncation-fields.md) | Bodies carry a truncation flag and their original size | Accepted |
| [0021](0021-server-frame-limit-keep-alive-and-envelope-rules.md) | Server frame limit, keep-alive and envelope rules | Accepted |
| [0022](0022-sdk-connection-lifecycle.md) | SDK connection lifecycle, offline buffer and backoff | Accepted |
| [0023](0023-sdk-core-module-and-ktor-transport.md) | sdk/core module layout and its Ktor CIO transport | Accepted |
