# 04 — Testing and verification

What "done" means for a task.

## Rules

- **A task is done when its Accept line in the active plan (`docs/plans/`)
  is proven by a command**, and that command is in the PR's "What was
  actually run".
- **Every bug fix ships a regression test** that fails without the fix.
- **Protocol changes ship contract fixtures**: binary samples encoded by one
  side and decoded by the other (Kotlin ↔ Swift). Fields are never renumbered
  or reused.
- **End-to-end beats mocks for transport.** Connection, discovery and
  streaming are tested against a real desktop server (in-process or headless),
  not a mocked socket.
- **No test depends on a host outside the machine.** Unit, contract and end
  to end tests talk to servers the test or its script starts; a public
  service is never a test target.
- **Two suites (ADR-0076).** `make check` is the fast suite every PR to
  `dev` runs; simulator tests, sample apps and end to ends run in the
  release gate, and locally when a change touches them.
- **Flaky is broken.** A test that fails intermittently becomes a `fix/` task
  or a tech-debt item with a trigger — never a retry loop.
- **A test is not a stopwatch (ADR-0201).** A runner can stall for seconds.
  A bound shorter than a stall belongs only in the test of that bound; every
  other test takes one that holds through it (a session lost mid-test
  empties what the test waits on).
- **Wait for what you act on.** Before clicking, wait for the screen, not for
  the store behind it. A session that ends takes time to clean up: wait for
  that too before starting the next.
- **Measure a timer on its clock.** `Date` drifts against a dispatch timer;
  use the monotonic clock it runs on.
- **One try is not a test.** Whatever a test draws or finds once (a port, an
  endpoint) survives a failed try.
- **Repeat before you trust.** `make swift-stress` before opening a release,
  and for any iOS test that failed once: an idle Mac hides what a hosted
  runner shows.
- **Say what did not run.** If iOS tests could not run (no macOS, no
  simulator), the report says so.

## Examples

| Do | Don't |
| --- | --- |
| "Ran `./gradlew :sdk:core:jvmTest` (12 passed). iOS tests not run: no simulator." | "Tests pass." |
