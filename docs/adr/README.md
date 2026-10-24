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
| [0024](0024-sdk-discovery-contract.md) | One mDNS selection rule for every client, and the SDK's discovery sources | Accepted |
| [0025](0025-okhttp-capture-module.md) | OkHttp capture module, its bridge to sdk/core and the body cap | Accepted |
| [0026](0026-no-op-artifact-and-abi-dumps.md) | One no-op artifact, checked against committed ABI dumps | Accepted |
| [0027](0027-device-approval-on-the-desktop.md) | Device approval as a server hook, shown in the window | Accepted |
| [0028](0028-websocket-frame-truncation-fields.md) | WebSocket frames carry a truncation flag and their original size | Accepted |
| [0029](0029-desktop-renders-bodies-off-the-ui-thread.md) | The detail pane is a lazy list; bodies render off the UI thread in chunks | Accepted |
| [0030](0030-android-sample-app.md) | Android sample app, its variants and the release classpath check | Accepted |
| [0031](0031-live-device-screenshots.md) | End to end evidence from an offscreen render fed by a real device, by hand until M3 | Accepted |
| [0032](0032-url-user-info-masking.md) | What the SDK masks in a URL's user info | Accepted |
| [0033](0033-response-header-url-masking.md) | Which response headers the SDK masks as URLs | Accepted |
| [0034](0034-masking-off-switch.md) | A switch that turns every masking rule off | Accepted |
| [0035](0035-ios-sdk-public-api.md) | iOS SDK public API, package products and minimum iOS | Accepted |
| [0036](0036-ios-capture-masking-and-plist-keys.md) | What the iOS SDK captures and masks, and the Info.plist keys it needs | Accepted |
| [0037](0037-where-swift-tests-run.md) | Where the Swift tests run | Accepted |
| [0038](0038-capture-layer-field.md) | Exchanges carry the capture layer that produced them | Accepted |
| [0039](0039-swift-package-layout-and-codegen.md) | Swift package layout, protobuf code generation and Swift gates | Accepted |
| [0040](0040-swift-transport-network-framework.md) | Swift transport on Network.framework, confined to one serial queue | Accepted |
| [0041](0041-connection-refused-ends-connecting.md) | A refused connection ends connecting, other waits do not | Accepted |
| [0042](0042-bonjour-address-by-tcp-probe.md) | A Bonjour result's address comes from a TCP probe to its service | Accepted |
| [0043](0043-urlsession-capture-by-cfnetwork-hooks.md) | URLSession capture hooks task resume and CFNetwork's connection callbacks | Accepted |
| [0044](0044-non-http-location-masking.md) | Whether a custom-scheme Location gets the URL masking rule | Accepted |
| [0045](0045-urlsession-websocket-capture.md) | URLSession WebSocket tasks are captured by hooks on the task's own methods | Accepted |
| [0046](0046-background-session-capture-by-hooks.md) | Background URLSession capture hooks the session's callbacks from nsurlsessiond | Accepted |
| [0047](0047-ios-simulator-tests-hosted-by-xcodegen-app.md) | iOS simulator tests run in an XcodeGen test host app | Accepted |
| [0048](0048-ios-release-stubs-and-api-dump.md) | iOS release stubs under #if DEBUG, checked against a committed interface dump | Accepted |
| [0049](0049-local-network-keys-script.md) | Local network keys added to the built Info.plist by a Run Script phase | Accepted |
| [0050](0050-ios-sample-app.md) | iOS sample app from an XcodeGen spec, checked by a symbol scan of its release build | Accepted |
| [0051](0051-debt-trigger-check-by-backticked-names.md) | Open DEBT triggers are matched against changed files by their backticked names | Superseded by [0061](0061-debt-check-fails-on-unresolved-base.md) |
| [0052](0052-release-gate-runners-and-cost.md) | Release gate jobs, runners and minutes per run | Accepted |
| [0053](0053-gate-required-on-main-by-ruleset.md) | The release gate is required on main through the existing ruleset | Accepted |
| [0054](0054-sdk-publication-coordinates.md) | SDK publication coordinates and tag scheme | Accepted |
| [0055](0055-desktop-package-names.md) | Desktop package names, bundle id and unsigned dry-run packages | Accepted |
| [0056](0056-android-end-to-end-by-launch-extras.md) | Android end to end driven by launch extras, asserted on the headless server log | Accepted |
| [0057](0057-ios-end-to-end-by-launch-arguments.md) | iOS end to end driven by launch arguments, sharing the Android assertions | Accepted |
| [0058](0058-sdk-publication-dry-run.md) | SDK publication dry-run with maven-publish, a protocol artifact and a path SwiftPM consumer | Accepted |
| [0059](0059-no-trailer-check-on-release-prs.md) | The attribution trailer check runs on PRs to dev only | Superseded by [0060](0060-trailer-check-skipped-only-for-dev-to-main.md) |
| [0060](0060-trailer-check-skipped-only-for-dev-to-main.md) | The attribution trailer check is skipped only on dev → main PRs | Accepted |
| [0061](0061-debt-check-fails-on-unresolved-base.md) | The DEBT trigger check fails when its base does not resolve | Accepted |
