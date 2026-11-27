# Contributing to App Monitor

Thanks for your interest. This page gets you from a clean clone to a pull
request. The rules themselves live in [`AGENTS.md`](AGENTS.md) and
[`docs/guidelines/`](docs/guidelines/README.md); this page points to them
instead of repeating them.

## Before you start

- **A bug** — open a [Bug issue](https://github.com/christianalexandre/app-monitor/issues/new/choose)
  with what you saw and what you expected. A fix with a test that fails
  without it is welcome as a pull request.
- **A feature** — open a [Feature issue](https://github.com/christianalexandre/app-monitor/issues/new/choose)
  first. What App Monitor does next is planned in
  [`docs/plans/`](docs/plans/README.md), and a change to what an SDK
  captures, stores or exposes, or to its public API, is decided there before
  any code.

## Set up

| Tool | For |
| --- | --- |
| JDK 21 | Gradle and the desktop app |
| Android SDK (`ANDROID_HOME`), platform `android-36`, build tools 36.0.0 | every Gradle build |
| Xcode 26 and `brew install xcodegen` (macOS only) | the iOS SDK, its simulator tests and the iOS samples |
| GNU Make, `python3` | the commands below |

Gradle comes from the wrapper; no local install is needed. The full list,
with versions, is in [SDD 02](docs/sdd/02_development_environment.md#toolchain).

```sh
git clone https://github.com/christianalexandre/app-monitor.git
cd app-monitor
make check
```

`make check` is what every pull request runs: the docs checks, lint, the
script tests, the Gradle build with its tests, and the iOS SDK's host tests on
macOS (it prints `swift: SKIPPED` elsewhere). It needs no device.

## Find your way

- [`docs/sdd/03_code_map.md`](docs/sdd/03_code_map.md): what each module is
  and where its code lives.
- [`docs/sdd/`](docs/sdd/README.md): how the system works today.
- [`docs/adr/`](docs/adr/README.md): why it is built this way, one decision
  per file.
- [`docs/README.md`](docs/README.md): how the documentation is organised.

## Run it

| Command | What |
| --- | --- |
| `make desktop` | The desktop app, listening for devices |
| `make fake-traffic` | Recorded traffic sent to a running desktop, no device needed |
| `make server` | A headless desktop that prints what devices send |
| `make check-full` | `make check`, plus the iOS simulator tests and every sample app |
| `make help` | Every target |

The end to end runs, the sample apps against a desktop and every other
command are in the commands table of [`AGENTS.md`](AGENTS.md#commands).

## Make a change

1. **Branch from `dev`** as `<type>/<kebab-slug>`, the type from
   Conventional Commits: `fix/websocket-close-code`, `docs/ios-run-script`.
2. **Write the test first** when you fix a bug: it fails without the fix.
   [Guideline 04](docs/guidelines/04_testing_and_verification.md) says what
   counts as verified.
3. **Keep the docs with the code.** A change to how something works updates
   [`docs/sdd/`](docs/sdd/README.md); a change to what an app developer does
   updates [`docs/guide/`](docs/guide/README.md); a decision gets an ADR. All
   in the same pull request.
4. **Comments say what breaks if someone touches the line**, at most four
   lines, and cite `ADR-NNNN` for the why
   ([guideline 02](docs/guidelines/02_comments.md)).
5. **Run `make check`**, then `./gradlew spotlessApply` if lint complains.
6. **Open the pull request to `dev`** with a Conventional Commits title of 72
   characters at most. Its template asks what you ran: list the commands and
   their results, and what you did not run.

Everything in the repository is in English. The full branch, commit and
pull request rules are in
[guideline 03](docs/guidelines/03_git_and_pull_requests.md).

## Review

A pull request to `dev` needs three checks green:

- `conventions`: the branch name, the title, and no attribution trailer such
  as `Co-Authored-By` in its commits;
- `docs`: `make docs-check`;
- `check`: `make check`, on Linux.

The owner reviews it and merges it as a squash. Releases go from `dev` to
`main` through the release gate, and only the owner cuts them.

## License

By contributing, you agree that your contribution is licensed under the
[MIT license](LICENSE) of this repository.
