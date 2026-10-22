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
