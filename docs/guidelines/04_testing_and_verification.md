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
- **Flaky is broken.** A test that fails intermittently becomes a `fix/` task
  or a tech-debt item with a trigger — never a retry loop.
- **Say what did not run.** If iOS tests could not run (no macOS, no
  simulator), the report says so.

## Examples

| Do | Don't |
| --- | --- |
| "Ran `./gradlew :sdk:core:jvmTest` (12 passed). iOS tests not run: no simulator." | "Tests pass." |
