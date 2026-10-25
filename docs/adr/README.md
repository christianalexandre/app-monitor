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
| [0016](0016-bounded-milestone-review.md) | Milestone review is incremental, keeps only correctness findings and stops after three rounds | Superseded by [0094](0094-faster-loop.md) |
| [0017](0017-android-sdk-public-api.md) | Android SDK public API and minSdk | Accepted |
| [0018](0018-capture-masking-and-device-approval.md) | What the Android SDK captures and masks, and device approval | Accepted |
| [0019](0019-loop-merges-the-close-plan-pr.md) | The loop merges the close-plan PR and moves on to the next plan | Accepted |
| [0020](0020-body-truncation-fields.md) | Bodies carry a truncation flag and their original size | Accepted |
| [0021](0021-server-frame-limit-keep-alive-and-envelope-rules.md) | Server frame limit, keep-alive and envelope rules | Accepted |
| [0022](0022-sdk-connection-lifecycle.md) | SDK connection lifecycle, offline buffer and backoff | Accepted |
| [0023](0023-sdk-core-module-and-ktor-transport.md) | sdk/core module layout and its Ktor CIO transport | Accepted |
| [0024](0024-sdk-discovery-contract.md) | One mDNS selection rule for every client, and the SDK's discovery sources | Accepted |
| [0025](0025-okhttp-capture-module.md) | OkHttp capture module, its bridge to sdk/core and the body cap | Accepted |
| [0026](0026-no-op-artifact-and-abi-dumps.md) | One no-op artifact, checked against committed ABI dumps | Superseded by [0072](0072-no-op-per-adapter-and-klib-dumps.md) |
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
| [0062](0062-kmp-sdk-public-api.md) | KMP SDK public API, artifacts, no-op and iOS targets | Accepted |
| [0063](0063-ktor-capture-masking-and-ios-keys.md) | What the Ktor plugin captures and masks, and the keys a KMP iOS app needs | Accepted |
| [0064](0064-ktor-swift-dedupe-by-correlation-header.md) | How the Ktor plugin and the Swift SDK avoid capturing one exchange twice | Accepted |
| [0065](0065-kmp-sample-app.md) | KMP sample app, its UI toolkit, targets and place in the release gate | Accepted |
| [0066](0066-sdk-core-on-kotlin-native.md) | sdk/core on Kotlin/Native keeps CIO and runs its state tests in common code | Accepted |
| [0067](0067-sdk-core-ios-build-and-stop-while-connecting.md) | sdk/core iOS build, live simulator test, and stop() letting a connect end itself | Accepted |
| [0068](0068-kotlin-native-bonjour-resolves-with-dns-sd.md) | sdk/core on iOS browses with nw_browser and resolves with dns_sd, not a TCP probe | Accepted |
| [0069](0069-ktor-capture-hook-and-common-masking.md) | The Ktor plugin captures in the send pipeline and masks by RFC 3986 on strings | Accepted |
| [0070](0070-ktor-websocket-frames-from-the-engine-session.md) | The Ktor plugin records WebSocket frames on the engine's session | Accepted |
| [0071](0071-ktor-capture-header-over-an-in-process-mark.md) | The Ktor plugin marks its captures with the header, not an in-process mark | Accepted |
| [0072](0072-no-op-per-adapter-and-klib-dumps.md) | One no-op module per SDK module, checked against JVM, Android and klib dumps | Accepted |
| [0073](0073-kmp-sample-layout-and-release-swap.md) | KMP sample in three modules, the release swap per platform and compileSdk 37 | Accepted |
| [0074](0074-local-e2e-target-in-kotlin.md) | The end to ends call a local Ktor target passed to the samples at launch | Accepted |
| [0075](0075-kmp-end-to-end-one-script-and-captured-once.md) | The KMP end to end is one script per platform argument, and every end to end asserts each exchange once | Accepted |
| [0076](0076-fast-pr-suite-slow-suites-in-the-release-gate.md) | PRs to dev run a fast suite; slow suites run only in the release gate | Accepted |
| [0077](0077-hello-names-the-sdk.md) | Hello names the SDK that opened the session | Accepted |
| [0078](0078-device-id-published-by-hard-link.md) | The device id file is published by an exclusive hard link | Accepted |
| [0079](0079-ios-device-id-directory-without-dirname.md) | sdk/core on iOS creates the device id directory with NSFileManager, not kotlinx-io | Accepted |
| [0080](0080-device-id-file-lock-without-links.md) | Without hard links, sdk/core publishes the device id under a file lock | Accepted |
| [0081](0081-what-the-desktop-stores-on-disk.md) | What the desktop stores on disk, where, and how it is protected | Accepted |
| [0082](0082-storage-retention.md) | How long the desktop keeps captured traffic, and how it is cleared | Accepted |
| [0083](0083-persisted-device-approvals.md) | Device approvals survive a restart, and how one is revoked | Accepted |
| [0084](0084-what-a-run-is.md) | What a run is for the developer, and what the window shows by default | Superseded by [0140](0140-only-recordings-and-saved-exchanges-are-stored.md) |
| [0085](0085-desktop-storage-platform-primitives.md) | Desktop storage on SQLDelight 2.4.1 and sqlite-jdbc, its files and connection settings | Accepted |
| [0086](0086-storage-open-and-write-details.md) | How the store prepares a new file, stores booleans and counts lost writes | Accepted |
| [0087](0087-exchange-search-queries.md) | Exchange search is one WHERE built from the filters that are set | Accepted |
| [0088](0088-retention-pass-and-clear-all.md) | Retention measures live pages, deletes in chunks on the writer thread, and "Clear all data" empties the file in place | Accepted |
| [0089](0089-what-the-mcp-tools-return.md) | What the MCP tools return, and how bodies are capped | Accepted |
| [0090](0090-who-can-call-the-mcp-server.md) | Who can call the MCP server, on which port, and whether it is on by default | Accepted |
| [0091](0091-which-mcp-clients-and-setup-docs.md) | Which MCP clients the desktop serves, and what the setup docs cover | Accepted |
| [0092](0092-mcp-server-platform-primitives.md) | MCP server on the Kotlin MCP SDK 0.15.0, stateless Streamable HTTP on Ktor CIO | Accepted |
| [0093](0093-gradle-plugin-for-the-release-swap.md) | A Gradle plugin swaps the SDK for its no-ops in release builds | Accepted |
| [0094](0094-faster-loop.md) | The loop reviews a milestone once, merges on auto, plans fewer tasks and stops less | Accepted |
| [0095](0095-what-an-issue-keeps-and-who-writes-it.md) | What an issue keeps, how long, and who may write it | Accepted |
| [0096](0096-issue-rows-and-their-writers.md) | Issue rows, their writers' order, and what retention trims | Accepted |
| [0097](0097-endpoint-grouping-in-storage.md) | The endpoint grouping lives in desktop/storage | Accepted |
| [0098](0098-window-exports-through-desktop-mcp.md) | The window's HAR and cURL exports call desktop/mcp's code | Accepted |
| [0099](0099-grpc-swift-adapter.md) | Which grpc-swift the adapter targets, and how an app adds it | Accepted |
| [0100](0100-connection-metadata-layer.md) | What the connection layer captures on iOS, and when it is on | Accepted |
| [0101](0101-desktop-proxy-mode.md) | How the desktop proxy mode is exposed, trusted and fed | Accepted |
| [0102](0102-grpc-call-events.md) | How a grpc-swift call maps onto the network events | Accepted |
| [0103](0103-connection-events.md) | How a rebound socket maps onto the network events | Accepted |
| [0104](0104-connection-exchanges-in-the-desktop.md) | How the desktop reads a connection exchange | Accepted |
| [0105](0105-proxy-and-certificate-libraries.md) | Netty and Bouncy Castle for the desktop proxy | Accepted |
| [0106](0106-proxy-http2-and-grpc.md) | How the desktop proxy relays HTTP/2 and stores gRPC | Accepted |
| [0107](0107-grpc-status-in-the-desktop.md) | The desktop stores grpc-status in its own column | Accepted |
| [0108](0108-proxy-trust-on-the-simulator.md) | How a simulator's gRPC client trusts the proxy | Accepted |
| [0109](0109-grpc-core-in-the-ios-sample.md) | gRPC-Core in the iOS sample and the proxy in the iOS end to end | Accepted |
| [0110](0110-mock-rules.md) | Mock rules from the desktop, served by the SDKs | Accepted |
| [0111](0111-breakpoints-and-replay.md) | Breakpoints hold a call on the device; replay goes through the app | Accepted |
| [0112](0112-okhttp-injection-by-the-gradle-plugin.md) | The Gradle plugin adds the OkHttp interceptor in debug builds | Accepted |
| [0113](0113-interaction-channel-and-rule-storage.md) | The interaction channel's rule set and how the desktop keeps rules | Accepted |
| [0114](0114-mock-editor-and-create-mock.md) | The window's mock editor and the create_mock tool | Accepted |
| [0115](0115-mocks-in-the-kotlin-sdks.md) | How the Kotlin SDKs keep, match and serve mock rules | Accepted |
| [0116](0116-mocked-exchanges-in-the-desktop.md) | How the desktop keeps and shows a mocked exchange, and the headless server's rules | Accepted |
| [0117](0117-mocks-in-the-ios-sdk.md) | How the iOS SDK keeps, matches and serves mock rules | Accepted |
| [0118](0118-breakpoint-messages-and-held-calls.md) | Breakpoints as their own rule set; a held call named by its request and stage | Accepted |
| [0119](0119-breakpoints-in-the-window.md) | A held call opens on its own; breakpoints get their own panel | Accepted |
| [0120](0120-replay-messages-and-send-again.md) | Replay named by request id, refused by the device, linked within its session | Accepted |
| [0121](0121-replay-from-mcp.md) | A replay an agent asks waits in memory for a click, and the tool answers at once | Accepted |
| [0122](0122-replay-in-the-kotlin-sdks.md) | How the Kotlin SDKs keep and send a request again | Accepted |
| [0123](0123-breakpoints-in-the-kotlin-sdks.md) | How the Kotlin SDKs hold a call at a breakpoint | Accepted |
| [0124](0124-replay-in-the-ios-sdk.md) | How the iOS SDK keeps and sends a task again | Accepted |
| [0125](0125-breakpoints-in-the-ios-sdk.md) | How the iOS SDK holds a task at a breakpoint | Accepted |
| [0126](0126-how-the-gradle-plugin-finds-the-sdk-and-is-tested.md) | How the Gradle plugin finds the SDK and is tested | Accepted |
| [0127](0127-the-gradle-plugin-rewrites-the-okhttp-client-constructor.md) | The Gradle plugin rewrites OkHttp's client constructor | Accepted |
| [0128](0128-a-network-interceptor-serves-no-mock-and-keeps-the-host.md) | As a network interceptor, the OkHttp capture serves no mock and keeps the host | Accepted |
| [0129](0129-ktor-marks-its-captures-beside-the-okhttp-sdk.md) | The Ktor plugin marks its captures beside the OkHttp SDK, and the interceptor skips and strips the mark | Accepted |
| [0130](0130-a-held-ios-task-asks-the-apps-delegate.md) | A held iOS task asks the app's delegate | Accepted |
| [0131](0131-background-tasks-resumed-before-start-are-adopted.md) | Background tasks the resume hook never saw are adopted at their first callback | Accepted |
| [0132](0132-cleartext-allowed-by-a-debug-source-set-config.md) | Cleartext to the desktop is allowed by a debug source set config, not debug-overrides | Accepted |
| [0133](0133-desktop-clean-architecture-and-design-system.md) | The desktop app is split into a design system, core layers and feature modules | Accepted |
| [0134](0134-the-design-system-wraps-material-and-is-checked-by-a-script.md) | The design system wraps Compose Material and is checked by a script | Accepted |
| [0135](0135-desktop-domain-models-mirror-the-store-and-keep-protocol-messages.md) | Desktop domain models mirror the store's and keep the protocol's messages | Accepted |
| [0136](0136-desktop-view-models-are-wired-by-a-koin-dsl-module-per-window.md) | Desktop view models are wired by a Koin DSL module per window | Accepted |
| [0137](0137-what-desktop-features-share-lives-in-desktop-ui.md) | What desktop features share lives in desktop/ui | Accepted |
| [0138](0138-mcp-reads-through-a-paged-query-repository.md) | MCP reads through a paged query repository beside the window's | Accepted |
| [0139](0139-live-traffic-by-default-and-runs-as-recordings.md) | The window shows live traffic by default; a run is a recording started and stopped by hand | Superseded by [0140](0140-only-recordings-and-saved-exchanges-are-stored.md) |
| [0140](0140-only-recordings-and-saved-exchanges-are-stored.md) | Live traffic stays in memory; only recordings and what the user saves are stored | Accepted |
| [0141](0141-the-live-list-is-held-as-the-store-s-rows-in-memory.md) | The live list is held as the store's rows in memory, its ids apart from the store's | Accepted |
