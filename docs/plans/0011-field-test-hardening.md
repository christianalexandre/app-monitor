---
id: PLAN-0011
title: Hardening before the field test
status: Done # Draft | Active | Done | Abandoned
milestone: H1
started: 2026-10-25
finished: 2026-10-25
---

# PLAN-0011 — Hardening before the field test (H1)

## Goal

What a developer meets when adding the SDKs to a real app, rather than a
sample, works: setup, approval, capture in OkHttp, Ktor and `URLSession`,
and the Gradle plugin. The open DEBTs that a real app hits on that path
are paid. Asked by the owner before the roadmap's Hold (field test), so
that the field test finds the app's problems, not known ones.

## Tasks

No new feature. Each DEBT task pays the DEBT and fills its "How it was
paid". H1.1's findings that normal use hits become `H1.R*` tasks here,
at most two; the rest go into one DEBT (ADR-0094). Non-correctness review
DEBTs (DEBT-0049 … DEBT-0088 cleanups) stay out.

- [x] **H1.1 Review the field-test path.** (#254) `code-review` at `medium` over
  what a real app runs, not one milestone's diff: the setup steps of the
  README and SDD 02 (dependency, Gradle block or plugin, Run Script,
  start), discovery and approval, the transport, and the capture in
  `sdk/android-okhttp`, `sdk/ktor`, `sdk/ios` and `gradle-plugin/`.
  Findings committed in `docs/plans/` as `H1.R*` tasks or one DEBT.
  Accept: the findings list in the PR, each with its failure scenario,
  triaged by the ADR-0094 bar.
- [x] **H1.2 Ktor on OkHttp is captured once.** (#255) DEBT-0087: the Ktor plugin
  marks its request on Android too, and `AppMonitorInterceptor` skips a
  marked request.
  Accept: `./gradlew :sdk:ktor:allTests :sdk:android-okhttp:allTests` — a
  Ktor client on the OkHttp engine with both SDKs installed sends one
  exchange per call; the mark never reaches the server.
- [x] **H1.3 A held iOS task asks the app's delegate.** (#256) DEBT-0085:
  `HoldProtocol` hands authentication challenges and redirects to the
  app's session delegate.
  Accept: `swift test --package-path sdk/ios` — a held task whose
  delegate rejects a challenge fails as without the breakpoint, and a
  redirect the delegate refuses is not followed.
- [x] **H1.4 Background tasks from before `start`.** (#257) DEBT-0039: a
  background transfer resumed before `AppMonitor.start`, or in an earlier
  launch, is captured when it completes, with its start time marked as
  unknown rather than invented.
  Accept: `make swift-sim` — a background task resumed before `start`
  reaches the stand-in desktop at completion.
  Deviation: the start time is the real one, from the task's metrics,
  rather than marked unknown (ADR-0131).
- [x] **H1.R1 The setup steps a real app needs are in the README.** (#258) The
  README's Android setup omits the debug `network-security-config` with
  `<debug-overrides>` and the runtime `ACCESS_LOCAL_NETWORK` request; the
  iOS one names the Run Script but not its input and output files under
  `ENABLE_USER_SCRIPT_SANDBOXING`; the KMP swap snippet lacks the sample's
  `KOTLIN_FRAMEWORK_BUILD_TYPE` fallback. The cleartext log of
  `MonitorClient.kt` points to an "SDK README" that does not exist.
  Accept: each README step matches what `samples/android`, `samples/ios`
  and `samples/kmp` do, listed side by side in the PR; the cleartext log
  names a document that exists (`./gradlew :sdk:core:allTests`);
  `make docs-check`.
  Deviation: `<debug-overrides>` cannot allow cleartext (trust anchors
  only), so the README shows the samples' debug source set config and
  ADR-0132 corrects ADR-0017 F's snippet.
- [x] **H1.R2 iOS connection capture skips the KMP SDK's own socket.**
  ADR-0100 C.1: the SDK's own desktop socket is never reported, but
  `ConnectionCapture.connected` skips only loopback peers. On a physical
  iPhone, `sdk/core`'s CIO socket to the desktop is reported.
  Accept: `swift test --package-path sdk/ios` — a connect to a desktop
  the SDK discovered, on a non-loopback address, is not reported; another
  host on the same address and a different port still is.

### H1 milestone review

`code-review` at `medium` of the field-test path (H1.1), not of a diff:
`sdk/android-okhttp`, `sdk/ktor`, `sdk/core`, `sdk/ios/Sources/AppMonitor`
(generated code excluded), `gradle-plugin/src/main`, the README and SDD 02.
Already open DEBT-0039, DEBT-0085, DEBT-0086 and DEBT-0087 were not
reported again. Nine findings:

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | Cleartext `ws://` blocked on a targetSdk ≥ 28 app that follows the README: the client stops for good, and the log points to a missing README. `ACCESS_LOCAL_NETWORK` is not in the README either. | Yes: no capture at all | H1.R1 |
| 2 | Run Script without input and output files fails a sandboxed Debug build (`plutil` denied). | Yes: a new Xcode project | H1.R1 |
| 3 | The README's KMP snippet picks `ktor-no-op` only for `CONFIGURATION == release`; a `Staging` configuration links the real SDK (Debug only). | Yes: privacy | H1.R1 |
| 4 | On a physical iPhone, a KMP app with the Swift SDK reports `sdk/core`'s own desktop socket as a connection. | Yes: wrong data shown | H1.R2 |
| 5 | After the cleartext stop, `MonitorHolder` still holds the stopped client: `start` is ignored and capture keeps working for envelopes that are dropped. | No: fixing the policy needs a rebuild, which starts afresh | DEBT-0089 |
| 6 | The Swift client never resets `connectFailureLogged` after a `HelloAck`, so only its first connect failure is ever logged. | No: diagnostic only | DEBT-0089 |
| 7 | `AppMonitorInterceptor` captures the request body again for each breakpoint event, up to three writes. | No: cost only, bodies are re-readable | DEBT-0089 |
| 8 | Replay closures keep 200 requests with their bodies. | — | Rejected: DEBT-0088 has it |
| 9 | A frame taken from the buffer and lost with its socket is not counted. | — | Rejected: SDD 04 limits, DEBT-0023 |

## Outcome

- **Shipped**: plan (#253); H1.1 the field-test path review (#254); H1.2
  Ktor on OkHttp captured once (#255); H1.3 a held iOS task asks the
  app's delegate (#256); H1.4 iOS background tasks from before `start`
  (#257); H1.R1 the setup a real app needs in the README and a cleartext
  log naming a real fix (#258); H1.R2 another SDK's socket to a desktop
  the iOS SDK found not reported (this PR, which closes the plan).
- **Decisions**: ADR-0129 (Ktor marks its captures beside the OkHttp
  SDK), ADR-0130 (a held iOS task asks the app's delegate), ADR-0131
  (background tasks resumed before `start` adopted), ADR-0132 (cleartext
  allowed by a debug source set config, correcting ADR-0017 F's snippet).
- **Debt**: DEBT-0039, DEBT-0085 and DEBT-0087 paid, DEBT-0060 paid by
  #258, DEBT-0081's `ConnectionCapture.swift` item by this PR. DEBT-0089
  created with the review's three non-correctness findings. DEBT-0090
  created: a desktop socket another SDK opens before the iOS SDK's first
  mDNS answer for it is still reported, a start-up ordering the review
  bar puts in a DEBT. No correctness finding that
  normal use hits is left open.
- **Deviations**: H1.4's start time is the task's real one, from its
  metrics, rather than marked unknown (ADR-0131). H1.R1 found that
  `<debug-overrides>` cannot allow cleartext and showed the samples'
  debug source set config instead (ADR-0132). DEBT-0086 stayed open: it
  needs an end to end that holds a call, not a field-test fix.
- **Lessons**: reviewing the path a real app runs, rather than one
  milestone's diff, found four problems the samples hid, three of them
  in the README. A sample that works proves the code, not the
  instructions; the field test should start from the README alone.
