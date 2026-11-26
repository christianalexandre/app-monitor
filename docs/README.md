# Documentation

Using App Monitor in an app: the [guides](guide/README.md). The rest of this
folder is how the project is built.

## How this project is built

App Monitor is developed by one person with an AI agent (Claude Code) working
in a documented loop. The process is part of the portfolio:

- **[`AGENTS.md`](../AGENTS.md)** — the rules any agent follows: what it may
  decide alone, when it must stop and ask, how it reports.
- **[Plans](plans/README.md)** — one numbered plan per milestone. Every task
  has an acceptance command; finished plans stay in the repo with their
  outcome, as history. The roadmap is there too.
- **[Decision records](adr/README.md)** — why the design is what it is.
- **[Tech debt](tech-debt/README.md)** — known gaps, each with the event
  that forces it to be paid.
- **Fresh-context loop** — `make loop` runs one task per session; the docs are
  the only memory, so they stay current.
- **Conventions enforced by CI** — semantic branches, Conventional Commits,
  one PR template per change type, protected `dev` and `main`.
- **Release gate** — `main` requires
  [`release-gate.yml`](../.github/workflows/release-gate.yml) green: desktop
  ×3 OS, contract tests, every sample app, Android, iOS and KMP end to end,
  packages, SDK dry-run.

The project is not open to contributions yet. To work on it locally:

```sh
make help        # list commands
make check       # fast suite, what PRs to dev run
make check-full  # plus simulator tests and every sample app
```

Toolchain: [development environment](sdd/02_development_environment.md).
Workflow: [git and pull requests](guidelines/03_git_and_pull_requests.md).

## The folders

Six folders. Five of them have a `README.md` (their rules and index) and a
`_template.md` (where every new file is born); `guide/` has only its index.

| Where | Answers | Nature |
| --- | --- | --- |
| [`sdd/`](sdd/README.md) | "What is the system and how do I work on it?" | Descriptive. Current state. |
| [`guidelines/`](guidelines/README.md) | "How do I write this?" | Prescriptive. |
| [`adr/`](adr/README.md) | "Why is it like this?" | One decision per file, immutable once accepted. |
| [`tech-debt/`](tech-debt/README.md) | "What is known to be missing?" | Conscious debt, **with a trigger**. |
| [`plans/`](plans/README.md) | "What are we building, and how was it built?" | One plan per milestone; immutable once done. Roadmap in the README. |
| [`guide/`](guide/README.md) | "How do I use it in my app?" | For app developers. Current state, linked from the root README (ADR-0220). |

### Two naming conventions

| Folder | Name | The number is |
| --- | --- | --- |
| `adr/`, `tech-debt/`, `plans/` | `0001-short-slug.md` | **identity** — never reused; file has frontmatter (`id`, `status`) |
| `sdd/`, `guidelines/` | `01_topic.md` | **reading order** — chapters may be merged or renumbered; no frontmatter |

### The flow

```
task that decides something → ADR (Accepted, or Proposed if it needs the owner)
                                   ↓
                     sdd/ and guidelines/ reflect it, in the same PR
```

Tasks that just follow the existing design need no ADR.

`make docs-check` validates frontmatter, indexes and references.
