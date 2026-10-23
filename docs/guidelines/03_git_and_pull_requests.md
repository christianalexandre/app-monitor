# 03 — Git and pull requests

How work leaves and comes back to `dev`.

## Branches

- **`main`** — released code. Receives only `dev`, through a release PR that
  passes the release gate (roadmap in `../plans/`, milestone M3).
- **`dev`** — integration, default branch. Receives only PRs from work
  branches.
- **Work branches**: `<type>/<kebab-slug>`, created from `dev`, merged back to
  `dev`. Slug in English, 2–6 words, lowercase, `a-z0-9-`.

| Type | Use |
| --- | --- |
| `feat` | New user-visible capability (SDK or desktop) |
| `fix` | Bug fix |
| `refactor` | Code change with no behavior change |
| `perf` | Performance improvement |
| `test` | Tests only |
| `docs` | Docs only (ADR, SDD, guidelines, README) |
| `build` | Gradle, SwiftPM, packaging |
| `ci` | GitHub Actions |
| `chore` | Tooling, config, repo housekeeping |
| `release` | `release/x.y.z`, only for the `dev → main` PR when a version bump commit is needed |

`make branch-check` validates the current branch name.

## Commits

- **Conventional Commits**: `type(scope): summary`, imperative, lowercase
  summary, no period, ≤ 72 chars. Scope is the module (`sdk-ios`, `desktop`,
  `protocol`, `docs`…), optional.
- Small, coherent commits. Each one builds.
- **Never** add `Co-Authored-By` or "Generated with" trailers.

## Pull requests

- **Base is `dev`**, except the release PR (`dev → main`).
- **Title** follows Conventional Commits — it becomes the squash commit.
- **Body** from the template for the branch type:
  `.github/PULL_REQUEST_TEMPLATE/<type>.md`. `/pr` picks it automatically.
- Every PR states **what was actually run** and what was not.
- Every PR answers the open DEBTs whose trigger names a file it changes
  (`make debt-check`, ADR-0051): paid, re-triggered, or not fired and why.
- **Squash merge** into `dev`. **Merge commit** for `dev → main`, so release
  history keeps every change.
- Delete the work branch after merge.

## Examples

| Do | Don't |
| --- | --- |
| `feat/ios-grpc-connection-metadata` | `feature/iOS_gRPC`, `christian/test` |
| `fix(sdk-android): rediscover desktop after wifi change` | `Fixed bug.` |
