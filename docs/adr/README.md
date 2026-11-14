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
| [0015](0015-loop-timeout-by-exit-status.md) | Loop reports a timeout only when the watchdog's TERM killed the session | Superseded by [0196](0196-loop-watchdog-ends-the-sessions-whole-tree.md) |
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
| [0039](0039-swift-package-layout-and-codegen.md) | Swift package layout, protobuf code generation and Swift gates | Superseded by [0160](0160-the-ios-sdk-encodes-its-protocol-without-swiftprotobuf.md) |
| [0040](0040-swift-transport-network-framework.md) | Swift transport on Network.framework, confined to one serial queue | Accepted |
| [0041](0041-connection-refused-ends-connecting.md) | A refused connection ends connecting, other waits do not | Accepted |
| [0042](0042-bonjour-address-by-tcp-probe.md) | A Bonjour result's address comes from a TCP probe to its service | Accepted |
| [0043](0043-urlsession-capture-by-cfnetwork-hooks.md) | URLSession capture hooks task resume and CFNetwork's connection callbacks | Accepted |
| [0044](0044-non-http-location-masking.md) | Whether a custom-scheme Location gets the URL masking rule | Accepted |
| [0045](0045-urlsession-websocket-capture.md) | URLSession WebSocket tasks are captured by hooks on the task's own methods | Accepted |
| [0046](0046-background-session-capture-by-hooks.md) | Background URLSession capture hooks the session's callbacks from nsurlsessiond | Accepted |
| [0047](0047-ios-simulator-tests-hosted-by-xcodegen-app.md) | iOS simulator tests run in an XcodeGen test host app | Accepted |
| [0048](0048-ios-release-stubs-and-api-dump.md) | iOS release stubs under #if DEBUG, checked against a committed interface dump | Superseded by [0155](0155-ios-sdk-left-out-of-release-builds-by-the-app.md) |
| [0049](0049-local-network-keys-script.md) | Local network keys added to the built Info.plist by a Run Script phase | Superseded by [0155](0155-ios-sdk-left-out-of-release-builds-by-the-app.md) |
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
| [0061](0061-debt-check-fails-on-unresolved-base.md) | The DEBT trigger check fails when its base does not resolve | Superseded by [0198](0198-a-trigger-path-from-the-root-matches-only-there.md) |
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
| [0093](0093-gradle-plugin-for-the-release-swap.md) | A Gradle plugin swaps the SDK for its no-ops in release builds | Superseded by [0157](0157-the-gradle-plugin-adds-the-sdk-and-fails-a-release-that-keeps-it.md) |
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
| [0132](0132-cleartext-allowed-by-a-debug-source-set-config.md) | Cleartext to the desktop is allowed by a debug source set config, not debug-overrides | Superseded by [0156](0156-android-sdk-asks-for-its-permission-and-ignores-the-cleartext-policy.md) |
| [0133](0133-desktop-clean-architecture-and-design-system.md) | The desktop app is split into a design system, core layers and feature modules | Accepted |
| [0134](0134-the-design-system-wraps-material-and-is-checked-by-a-script.md) | The design system wraps Compose Material and is checked by a script | Accepted |
| [0135](0135-desktop-domain-models-mirror-the-store-and-keep-protocol-messages.md) | Desktop domain models mirror the store's and keep the protocol's messages | Accepted |
| [0136](0136-desktop-view-models-are-wired-by-a-koin-dsl-module-per-window.md) | Desktop view models are wired by a Koin DSL module per window | Accepted |
| [0137](0137-what-desktop-features-share-lives-in-desktop-ui.md) | What desktop features share lives in desktop/ui | Accepted |
| [0138](0138-mcp-reads-through-a-paged-query-repository.md) | MCP reads through a paged query repository beside the window's | Accepted |
| [0139](0139-live-traffic-by-default-and-runs-as-recordings.md) | The window shows live traffic by default; a run is a recording started and stopped by hand | Superseded by [0140](0140-only-recordings-and-saved-exchanges-are-stored.md) |
| [0140](0140-only-recordings-and-saved-exchanges-are-stored.md) | Live traffic stays in memory; only recordings and what the user saves are stored | Accepted |
| [0141](0141-the-live-list-is-held-as-the-store-s-rows-in-memory.md) | The live list is held as the store's rows in memory, its ids apart from the store's | Accepted |
| [0142](0142-a-recorder-sink-stores-what-starts-while-recording.md) | A Recorder sink stores what starts while recording, one session row per run | Accepted |
| [0143](0143-save-copies-live-exchanges-into-one-saved-run.md) | Save copies live exchanges into one "Saved" run, marked by a column | Accepted |
| [0144](0144-disconnect-refuses-a-pair-in-memory-until-reconnect.md) | Disconnect refuses a pair in memory, before approval, until Reconnect | Accepted |
| [0145](0145-the-theme-is-a-stored-setting-applied-at-the-window-root.md) | The theme is a stored setting applied at the window root, every color a token with two values | Accepted |
| [0146](0146-a-toolbar-over-the-panes-and-request-rows-with-a-status-badge.md) | A toolbar over the panes, request rows with a status badge, the URL cut in its middle | Accepted |
| [0147](0147-request-detail-tabs-with-copy-actions.md) | Request detail in tabs, each part copied as the device sent it | Accepted |
| [0148](0148-a-filter-sheet-keeps-any-of-the-picked-status-classes-and-hosts.md) | A filter sheet keeps any of the picked status classes and hosts | Accepted |
| [0149](0149-a-neutral-gray-and-muted-blue-palette-replaces-material-s-defaults.md) | A neutral gray and muted blue palette replaces Material's default colors | Superseded by [0202](0202-a-desktop-type-scale-and-the-mockup-s-layered-palette.md) |
| [0150](0150-the-macos-title-bar-takes-the-stored-theme-at-launch.md) | The macOS title bar takes the stored theme at launch | Accepted |
| [0151](0151-panes-resize-by-dragging-and-the-devices-pane-hides.md) | Panes resize by dragging their rule, and the devices pane hides | Superseded by [0205](0205-a-sidebar-a-title-bar-and-a-status-bar-around-the-traffic.md) |
| [0152](0152-a-search-inside-the-request-and-code-blocks-for-its-texts.md) | A search inside the request, header rows, and code blocks with JSON colors | Accepted |
| [0153](0153-field-test-polish-of-the-traffic-window.md) | Field test polish of the traffic window, the panes kept across launches | Accepted |
| [0154](0154-the-ios-sample-builds-in-its-own-release-gate-job.md) | The iOS sample builds in its own release gate job | Accepted |
| [0155](0155-ios-sdk-left-out-of-release-builds-by-the-app.md) | The iOS SDK is left out of release builds by the app, guarded by a Run Script pasted from the README | Accepted |
| [0156](0156-android-sdk-asks-for-its-permission-and-ignores-the-cleartext-policy.md) | The Android SDK asks for its local network permission and no longer stops on the cleartext policy | Accepted |
| [0157](0157-the-gradle-plugin-adds-the-sdk-and-fails-a-release-that-keeps-it.md) | The Gradle plugin adds the SDK, swaps it for the no-ops outside debug, and fails a release that keeps it | Superseded by [0161](0161-release-binaries-and-dynamic-features-hold-no-sdk.md) |
| [0158](0158-a-kmp-app-on-ios-is-captured-by-the-swift-sdk-alone.md) | A KMP app on iOS is captured by the Swift SDK alone | Accepted |
| [0159](0159-okhttp-websockets-captured-by-the-gradle-plugin.md) | OkHttp WebSockets are captured by the Gradle plugin, at OkHttpClient.newWebSocket | Accepted |
| [0160](0160-the-ios-sdk-encodes-its-protocol-without-swiftprotobuf.md) | The iOS SDK encodes its protocol with generated code of its own, without SwiftProtobuf | Accepted |
| [0161](0161-release-binaries-and-dynamic-features-hold-no-sdk.md) | KMP release binaries link the no-ops at their link task, and dynamic features hold no SDK | Accepted |
| [0162](0162-until-m10-the-readme-installs-from-maven-local-and-the-dev-branch.md) | Until M10 the README installs from Maven Local and the dev branch, and the SDK says what setup is missing | Accepted |
| [0163](0163-grpc-core-leaves-the-ios-end-to-end-for-a-gate-job-of-its-own.md) | gRPC-Core leaves the iOS sample and end to end for a release gate job of its own | Accepted |
| [0164](0164-one-lan-address-filter-in-desktop-server.md) | One LAN address filter, in `desktop/server`, without tunnels | Accepted |
| [0165](0165-hello-carries-the-app-name-the-options-set.md) | Hello carries the app name the options set, and the desktop shows it before the app id | Accepted |
| [0166](0166-the-device-reports-what-it-dropped-in-a-delivery-report.md) | The device reports what it dropped in a DeliveryReport, the desktop counts what it lost | Accepted |
| [0167](0167-the-desktop-reads-device-frames-over-a-raw-websocket.md) | The desktop reads device frames over a raw WebSocket session and answers the close after them | Accepted |
| [0168](0168-a-device-pins-the-first-desktop-that-approved-it.md) | A device pins the first desktop that approved it | Accepted |
| [0169](0169-release-builds-keep-their-guarantees.md) | The no-op check reads constructors from the classes, the plugin rewrites OkHttp for its own SDK version, and the SDK compiles to Kotlin 2.3 | Accepted |
| [0170](0170-the-ios-sdk-suite-runs-on-the-simulator.md) | The iOS SDK's package suite runs on the simulator too, on the iOS SIM_OS picks | Accepted |
| [0171](0171-the-ios-floor-the-sdk-declares.md) | The iOS floor the SDK declares, against the runtimes it can be tested on | Accepted |
| [0172](0172-a-lost-count-waits-for-the-previous-session-to-close.md) | A session's lost count waits for its previous session to close | Accepted |
| [0173](0173-the-sdks-log-lines-are-public.md) | The SDKs' log lines are readable outside a debugger | Accepted |
| [0174](0174-mdns-failure-does-not-stop-the-server.md) | An mDNS failure does not stop the server | Accepted |
| [0175](0175-a-lock-file-holds-the-data-directory.md) | A lock file holds the data directory | Accepted |
| [0176](0176-the-headless-server-is-a-tool-module.md) | The headless server is a tool module | Accepted |
| [0177](0177-text-search-and-failed-only-read-compact-indexes.md) | Text search and "failed only" read compact indexes | Accepted |
| [0178](0178-the-macos-title-bar-follows-a-picked-theme-through-jna.md) | The macOS title bar follows a picked theme through JNA | Accepted |
| [0179](0179-a-write-committed-as-the-store-closes-is-answered-saved.md) | A write committed as the store closes is answered saved | Accepted |
| [0180](0180-up-and-down-move-a-rule-in-its-apps-order.md) | "Up" and "Down" move a rule in its app's order | Accepted |
| [0181](0181-one-kotlin-masking-in-sdk-core.md) | One Kotlin masking in sdk/core, each library resolving its own Location | Accepted |
| [0182](0182-one-call-capture-flow-in-sdk-core.md) | One capture flow in sdk/core, inline so a call may block or suspend | Accepted |
| [0183](0183-a-ktor-engine-known-by-its-config.md) | A Ktor engine is known by its config, so a wrapper of OkHttp's is marked too | Accepted |
| [0184](0184-the-monitors-connect-has-one-timeout-its-own.md) | The monitor's connect has one timeout, its own, not CIO's | Accepted |
| [0185](0185-what-an-edit-left-masked-goes-out-as-the-devices-own.md) | What an edit left masked goes out as the device's own | Accepted |
| [0186](0186-an-ios-replay-catches-the-sessions-raise.md) | An iOS replay catches the session's raise in Objective-C | Accepted |
| [0187](0187-one-method-hook-for-the-ios-capture.md) | One method hook for the iOS capture | Accepted |
| [0188](0188-a-held-call-in-the-ios-end-to-end.md) | A held call in the iOS end to end | Accepted |
| [0189](0189-ios-headers-sent-read-from-the-transaction-metrics.md) | iOS headers sent are read from the transaction metrics at the response | Accepted |
| [0190](0190-ios-websocket-ends-read-at-the-tasks-finish.md) | An iOS WebSocket's end is read where the task finishes | Accepted |
| [0191](0191-the-proxy-relays-at-the-slower-sides-pace.md) | The proxy relays at the slower side's pace and cuts an exchange it dropped an event of | Accepted |
| [0192](0192-a-reused-socket-number-ends-its-old-connection.md) | A reused socket number ends its old connection at the next connect | Accepted |
| [0193](0193-the-proxy-reads-a-websockets-frames-as-they-cross.md) | The proxy reads a WebSocket's frames as they cross, inflating permessage-deflate | Accepted |
| [0194](0194-the-proxys-pass-through-list.md) | The proxy's pass-through list, matched at each CONNECT and relayed as raw bytes | Accepted |
| [0195](0195-a-tunnels-leaf-offers-what-its-origin-does.md) | A tunnel's leaf offers what its origin does, asked before the client's handshake | Accepted |
| [0196](0196-loop-watchdog-ends-the-sessions-whole-tree.md) | The loop's watchdog ends the session's whole process tree, and its kill is the timeout | Accepted |
| [0197](0197-saving-again-counts-the-frames-received.md) | Saving an exchange again stores the frames received since, counted by the writer | Accepted |
| [0198](0198-a-trigger-path-from-the-root-matches-only-there.md) | A DEBT trigger path that exists from the root matches only there | Accepted |
| [0199](0199-a-gzip-body-opens-in-the-detail-before-its-formats.md) | A gzip body opens in the detail before its formats, bounded | Accepted |
| [0200](0200-the-gate-fails-early-and-seeds-its-own-cache.md) | The release gate fails early and seeds its own cache | Accepted |
| [0201](0201-a-test-holds-through-a-stalled-runner.md) | A test holds through a stalled runner, and iOS suites are repeated before a release | Accepted |
| [0202](0202-a-desktop-type-scale-and-the-mockup-s-layered-palette.md) | A desktop type scale and the mockup's layered palette replace Material's type and ADR-0149's values | Accepted |
| [0203](0203-two-line-request-rows-and-the-list-header.md) | Two-line request rows that keep the path's end, and the list's own header | Accepted |
| [0204](0204-the-request-detail-as-the-mockup-a-bar-a-request-line-and-tables.md) | The request detail as the mockup, a bar, a request line and key/value tables | Accepted |
| [0205](0205-a-sidebar-a-title-bar-and-a-status-bar-around-the-traffic.md) | A sidebar, a title bar and a status bar around the traffic, the other screens behind "Actions" | Accepted |
| [0206](0206-a-command-palette-replaces-the-actions-menu.md) | A ⌘K command palette replaces the title bar's "Actions" menu | Accepted |
| [0207](0207-a-replays-note-on-every-tab-with-open.md) | A replay's note shows on every tab and opens the call it brought back | Accepted |
| [0208](0208-a-body-opens-as-json-when-it-parses-and-long-values-are-cut.md) | A body opens as JSON whenever it parses, its lines wrap and long string values are cut | Accepted |
| [0209](0209-vector-icons-in-the-design-system-and-every-host-hidden.md) | Vector icons in the design system, and a filter that hides every host | Accepted |
| [0210](0210-the-title-bar-holds-the-tools-folded-under-a-narrow-window.md) | The title bar holds the tools as icon buttons with tooltips, folded into "⋯" under a narrow window | Accepted |
| [0211](0211-the-app-named-before-awt-starts-and-an-icon-rendered-from-one-svg.md) | The app is named before AWT starts, and its icon is rendered from one SVG | Accepted |
| [0212](0212-the-detail-opens-on-body-and-settings-draws-a-cog.md) | The detail opens on Body, and Settings draws a cog | Accepted |
| [0213](0213-on-macos-the-window-draws-its-own-title-bar.md) | On macOS the window draws its own title bar, through AWT's root pane properties | Accepted |
| [0214](0214-a-hidden-host-is-listed-not-the-shown-ones.md) | A hidden host is listed, not the hosts shown | Accepted |
| [0215](0215-maven-central-through-maven-publish-signing-and-the-portal-bundle-api.md) | Maven Central through maven-publish, Gradle signing and the Portal's bundle API | Accepted |
| [0216](0216-one-version-in-three-places-checked-by-a-script.md) | One version in three places, checked by a script; a release needs a matching X.Y.Z tag | Accepted |
| [0217](0217-the-publish-workflow-signs-in-ci-and-uploads-automatic.md) | The publish workflow signs on a main tag and uploads with publishingType AUTOMATIC; PRs run it dry | Accepted |
