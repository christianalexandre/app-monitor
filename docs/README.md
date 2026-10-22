# Documentation

Four folders and one plan. Each folder has a `README.md` (its rules and index)
and a `_template.md` (where every new file is born).

| Where | Answers | Nature |
| --- | --- | --- |
| [`sdd/`](sdd/README.md) | "What is the system and how do I work on it?" | Descriptive. Current state. |
| [`guidelines/`](guidelines/README.md) | "How do I write this?" | Prescriptive. |
| [`adr/`](adr/README.md) | "Why is it like this?" | One decision per file, immutable once accepted. |
| [`tech-debt/`](tech-debt/README.md) | "What is known to be missing?" | Conscious debt, **with a trigger**. |
| [`plan.md`](plan.md) | "What comes next?" | Milestones and task status. |

## Two naming conventions

| Folder | Name | The number is |
| --- | --- | --- |
| `adr/`, `tech-debt/` | `0001-short-slug.md` | **identity** — never reused; file has frontmatter (`id`, `status`) |
| `sdd/`, `guidelines/` | `01_topic.md` | **reading order** — chapters may be merged or renumbered; no frontmatter |

## The flow

```
task that decides something → ADR (Accepted, or Proposed if it needs the owner)
                                   ↓
                     sdd/ and guidelines/ reflect it, in the same PR
```

Tasks that just follow the existing design need no ADR.

`make docs-check` validates frontmatter, indexes and references.
