# Tech debt

What is known to be missing, accepted consciously. Every item has a
**trigger**: the event that forces it to be paid.

Rules:

- **Next number**: the highest in this folder + 1, four digits. Copy
  `_template.md` to `NNNN-kebab-slug.md`.
- **No trigger, no debt.** "When there is time" is not a trigger; "before the
  first public release" is.
- Paid or dropped: set `status` and `paid_date`, fill "How it was paid", and
  remove `DEBT-NNNN` references from code in the same PR.
- Check for an equivalent item before creating a new one.
- Add the row to the index below.

## Index

| Debt | Title | Trigger | Status |
| --- | --- | --- | --- |
| [0001](0001-jmdns-pinned-to-3-6-3.md) | JmDNS pinned to 3.6.3 | A JmDNS release newer than 3.6.4, or a security advisory for 3.6.3 | Open |
| [0002](0002-make-check-runs-full-build.md) | make check runs the full Gradle build | `make check` over 3 minutes on a warm cache, or an end to end target (`e2e_android.sh`, M3.7's iOS one) joins `make check` | Open |
| [0003](0003-websocket-frames-not-shown.md) | Desktop app ignores WebSocket frames | The first SDK that emits `WebSocketFrame` lands | Paid |
| [0004](0004-traffic-state-quadratic-ingest.md) | TrafficState ingest is linear per event | M5 replaces the in-memory store, or a session over 10k requests lags the UI | Paid |
| [0005](0005-duplicated-lan-interface-filter.md) | LAN interface filter duplicated in server and fake client | The first change to either filter, or a JVM client of `sdk/core` needs mDNS | Open |
| [0006](0006-body-rendering-unbounded.md) | Body rendering is synchronous and unbounded | The first SDK that captures real app bodies lands (M1), or a body freezes the detail pane | Paid |
| [0007](0007-mdns-failure-stops-server.md) | mDNS failure on one interface stops the whole server | A desktop reports "Server failed" because of one interface, or M1 SDKs connect by explicit host | Open |
| [0008](0008-accepted-channels-not-enforced.md) | Accepted channels are not enforced after the handshake | The first channel beyond `core` and `network` enters the protocol, or the M1 SDK sends envelopes | Paid |
| [0009](0009-discovery-ignores-late-txt.md) | Fake client discovery keeps the resolve before the TXT record | The advertised path can differ from `Protocol.WEBSOCKET_PATH`, or M1 `sdk/core` implements discovery | Paid |
| [0010](0010-headless-server-bind-failure-trace.md) | Headless server prints a stack trace when the port is taken | The headless server is started by something other than a developer at a terminal, or someone reports the trace as a bug | Open |
| [0011](0011-fake-client-discovery-listener.md) | Fake client discovery blocks the JmDNS listener and matches names by case | More than one `_appmonitor._tcp` service is seen on one LAN, or M1 `sdk/core` implements discovery | Paid |
| [0012](0012-mdns-instances-share-names.md) | Per-address mDNS instances share a host and service name | A desktop with two addresses on one LAN is seen renamed or flapping, or an SDK option filters by service name | Open |
| [0013](0013-loop-timeout-leaves-children.md) | Loop timeout kills only the session process | An iteration after a timeout fails on a held port or a Gradle lock | Open |
| [0014](0014-late-frame-rejection-reported-normal.md) | A rejection of a late frame reaches the client as a normal close | An SDK or a test needs to know the desktop rejected one of the last frames it sent | Open |
| [0015](0015-stop-bound-vs-jmdns-close.md) | The 5 s stop bound may cut a slow mDNS close | A "Server stop timed out" status or a shutdown hook timeout seen on a real desktop | Open |
| [0016](0016-loop-timeout-needs-exit-143.md) | A watchdog kill that does not exit 143 is not reported as a timeout | A loop session killed by the watchdog is logged as "no verdict line" instead of a timeout | Open |
| [0017](0017-spotless-walks-build-dir.md) | Spotless walks build directories while tests write them | The same spotless failure on a build file appears a second time, locally or in CI | Open |
| [0018](0018-empty-request-id-merges-exchanges.md) | Network events without a request id merge into one exchange | The M1 SDK starts sending network envelopes to the desktop | Paid |
| [0019](0019-sdk-bodies-not-masked.md) | The SDK masks headers and query parameters, never bodies | The first request to mask a field inside a request or response body | Open |
| [0020](0020-device-trusts-any-desktop.md) | A device sends its traffic to any desktop that advertises the service | Before the first public release of an SDK | Open |
| [0021](0021-sdk-frames-over-desktop-limit.md) | An SDK frame over the desktop's limit ends the session | M1.7 exposes `maxBodyBytes` in `sdk/android-okhttp` | Paid |
| [0022](0022-stalled-collector-misses-pongs.md) | A stalled collector makes a healthy device miss its ping timeout | The desktop logs "Storage queue was full" at close, or a session closed with 1011 "Ping timeout" while the device was reachable | Open |
| [0023](0023-sdk-event-loss-is-silent.md) | Events the SDK drops are invisible on the desktop | A developer reports an event missing from the desktop, or the protocol gains per-envelope sequence numbers | Open |
| [0024](0024-app-name-not-sent.md) | `AppMonitorOptions.appName` is accepted but never sent | The desktop shows an app by name (M1.9 approval dialog or later), or `Hello` gains an app name field | Open |
| [0025](0025-websocket-frames-cut-silently.md) | WebSocket frame payloads are cut without a marker, pings unseen | The desktop renders WebSocket frames (M1.10), or a frame over `maxBodyBytes` is reported shown whole | Paid |
| [0026](0026-abi-dump-misses-secondary-constructor.md) | The ABI dump can miss a public no-arg secondary constructor | The Kotlin version in `gradle/libs.versions.toml` changes, or a public SDK class gets a non-public primary constructor | Open |
| [0027](0027-stop-while-connecting-test-flaky.md) | `stopWhileConnectingClosesTheHalfOpenSocket` fails intermittently | CI fails on `MonitorClientTest`, or the next PR that touches `MonitorClient` or its stop path | Paid |
| [0028](0028-fake-client-handshake-timeout-vs-approval.md) | The fake client gives up on HelloAck after 10 s, approval included | M5.5 merged (an approved pair then skips the dialog), or a script runs the fake client against the app unattended | Open |
| [0029](0029-websocket-pings-not-captured.md) | WebSocket ping and pong frames are not captured | OkHttp exposes ping or pong frames to a listener, or a developer asks to see WebSocket keep-alive traffic | Open |
| [0030](0030-android-17-local-network-silent.md) | The SDK trusts the implicit local network grant and fails silently without it | PLAN-0002 M1.12 starts, or a developer reports an app that never reaches the desktop | Paid |
| [0031](0031-m1-review-round-1-cleanups.md) | Non-correctness findings of the first M1 milestone review | The next PR that touches one of the files listed | Open |
| [0032](0032-fake-client-close-test-flaky-in-ci.md) | The fake client's "desktop closes with an error" test failed once in CI | The second CI failure of `MainTest > a desktop that closes the session with an error exits 1`, or any failure of it on a developer machine | Open |
| [0033](0033-m1-review-round-2-cleanups.md) | Non-correctness findings of the second M1 milestone review | The next PR that touches one of the files listed | Open |
| [0034](0034-m1-review-round-3-cleanups.md) | Non-correctness findings of the third M1 milestone review | The next PR that touches one of the files listed | Paid |
| [0035](0035-non-http-location-not-masked.md) | A Location that is not an http or https URL is sent as received | The iOS SDK (M2) implements the URL masking rule, or a developer reports a secret seen in a custom-scheme redirect | Paid |
| [0036](0036-mdns-test-fails-with-a-vpn-up.md) | The desktop's mDNS test fails while a VPN interface is up | The next PR that changes either LAN interface filter (DEBT-0005), or a device fails to find the desktop while the Mac has a VPN up | Open |
| [0037](0037-urlsession-send-time-headers.md) | Headers CFNetwork adds at send time are not captured on iOS | M2.13 compares the desktop with a server log of the same request, or a developer reports a header missing from an iOS request | Open |
| [0038](0038-ios-websocket-ends-not-seen.md) | Some ends of an iOS WebSocket are not captured | M2.13 shows an iOS WebSocket ending differently from the Android sample's, or a developer reports an iOS WebSocket missing its close or failure | Open |
| [0039](0039-background-tasks-from-an-earlier-launch.md) | Background tasks resumed before start() or in an earlier launch are not captured | M2.12's background download is missing from the desktop after the sample is relaunched, or a developer reports a background transfer missing | Open |
| [0040](0040-ios-release-links-swiftprotobuf.md) | An iOS release build still links SwiftProtobuf | M10's first SwiftPM tag, or an app reports the SDK's size in a release build | Open |
| [0041](0041-ios-end-to-end-on-a-physical-iphone.md) | The iOS end to end has run on the simulator only, never on a physical iPhone | A physical iPhone is connected to the Mac that runs the loop, or M10's first tagged release | Open |
| [0042](0042-m2-review-round-1-cleanups.md) | Non-correctness findings of the first M2 milestone review | The next PR that touches `MonitorClient.swift` | Open |
| [0043](0043-m2-review-round-2-cleanups.md) | Non-correctness findings of the second M2 milestone review | The next PR after #114 that touches `Masking.swift`, or a run of the iOS SDK tests on an iOS 15 or 16 simulator | Open |
| [0044](0044-m2-review-round-3-cleanups.md) | Non-correctness findings of the third M2 milestone review | The next PR that touches `Masking.swift`, `sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/Capture.kt` or `protocol/fixtures/masking.json` | Open |
| [0045](0045-background-upload-progress-test-flaky.md) | The background upload test missed its progress callback once | The second failure of `aFileUploadIsCapturedBothWays` in `make swift-sim`, or the next PR that touches `BackgroundSessionTests.swift` | Open |
| [0046](0046-mdns-test-skipped-on-hosted-macos.md) | The mDNS round trip test is skipped on the hosted macOS runner | The runner image grants local network access (actions/runner-images#10924), or the next PR that touches `release-gate.yml` to add a job that needs Bonjour on macOS (M3.8) | Open |
| [0047](0047-android-e2e-needs-public-services.md) | The Android and iOS end to end depend on public services | The first `make e2e-android` or `make e2e-ios` failure caused by `httpbin.org` or `echo.websocket.org` | Paid |
| [0048](0048-ruleset-checks-not-matched-to-jobs.md) | The ruleset's required checks are not checked against the workflow job names | The next PR that renames a job or changes a matrix in `release-gate.yml` or `pr-conventions.yml`, or a release PR that waits on a required check that never reports | Open |
| [0049](0049-m3-review-round-1-cleanups.md) | Non-correctness findings of the first M3 milestone review | The first Android 17 preview image in use, or M10's first published desktop package | Open |
| [0050](0050-m3-review-round-2-cleanups.md) | Non-correctness findings of the second M3 milestone review | The next PR that touches `pr-conventions.yml` or `debt_check.sh`, or the first `release/*` branch opened to `main` | Open |
| [0051](0051-m3-review-round-3-cleanups.md) | Non-correctness findings of the third M3 milestone review | The next PR that touches `samples/android/src/main/AndroidManifest.xml`, or a rotation or process-death regression in the Android sample | Open |
| [0052](0052-kmp-ios-local-network-on-a-device.md) | The KMP SDK's BSD socket connection is unproven under local network privacy on a physical iPhone | A physical iPhone is connected to the Mac that runs the loop, or a developer reports the KMP SDK cannot reach the desktop from an iPhone | Open |
| [0053](0053-ktor-native-connect-cancel-leaks-socket.md) | A connect cancelled in its TCP phase may leak its socket on iOS | The `ktor` version in `libs.versions.toml` changes, or a KMP iOS app reports file descriptors or sockets growing across `AppMonitor.stop()` and `start()` | Open |
| [0054](0054-swift-bonjour-probe-unchecked-on-the-simulator.md) | The Swift SDK's Bonjour probe is not checked on the simulator | The next PR that touches `sdk/ios/Sources/AppMonitor/BonjourBrowser.swift`, or the first end to end that runs the Swift SDK on the simulator without a manual host | Open |
| [0055](0055-two-kotlin-masking-implementations.md) | Two Kotlin implementations of the masking rule | The next change to `protocol/fixtures/masking.json`, or a vector case that passes in one of `sdk/android-okhttp` and `sdk/ktor` and fails in the other | Open |
| [0056](0056-fake-client-close-reason-race.md) | The fake client can lose the desktop's close reason when the connection drops mid-send | A second CI or local failure of `HandshakeFailureTest`, or the next PR that touches `Replay.kt` | Open |
| [0057](0057-release-gate-macos-jobs-near-their-timeout.md) | The release gate's macOS jobs run close to their timeouts | A release gate run where `contract` or `e2e-kmp-ios` in `release-gate.yml` times out or takes more than 50 minutes | Paid |
| [0058](0058-m4-review-round-1-cleanups.md) | Non-correctness findings of the first M4 milestone review | The first desktop or MCP feature that groups exchanges by URL or matches a whole URL | Open |
| [0059](0059-kotlinx-io-apple-dirname-not-thread-safe.md) | kotlinx-io's Path.parent and Path.name are not thread-safe on Apple | The `kotlinx-io` version in `libs.versions.toml` changes, or a PR adds `Path.parent`, `Path.name` or `createDirectories` to `sdk/core/src/commonMain` or `sdk/core/src/iosMain` | Open |
| [0060](0060-m4-review-round-2-cleanups.md) | Non-correctness findings of the second M4 milestone review | The next PR that touches `sdk/core/src/commonTest/kotlin/appmonitor/sdk/core/MonitorClientTest.kt` or `scripts/e2e_lib.sh` | Open |
| [0061](0061-m4-review-round-3-cleanups.md) | Non-correctness findings of the third M4 milestone review | The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`, `sdk/core/src/jvmAndAndroidMain/kotlin/appmonitor/sdk/core/Platform.jvmAndAndroid.kt` or `sdk/core/src/androidMain/kotlin/appmonitor/sdk/core/Platform.android.kt` | Open |
| [0062](0062-second-desktop-on-one-data-directory.md) | A second desktop on the same data directory is not detected | The desktop's port becomes configurable in the app, or two desktops are seen writing the same `appmonitor.db` | Open |
| [0063](0063-text-search-walks-the-exchanges.md) | Text search and "failed only" walk the exchanges instead of an index | `SearchTest`'s no-match text search over 100 000 exchanges passes 100 ms, ADR-0082's size bound is raised past 1 GiB, or the MCP server (M6) exposes text search | Open |
