---
description: Opens the PR for the current branch with the template of its type
argument-hint: "[optional extra context for the description]"
allowed-tools: Bash, Read, Glob, Grep
---

Open a pull request for the current branch, following
`docs/guidelines/03_git_and_pull_requests.md`. Extra context: **$ARGUMENTS**

1. `make branch-check`. If the branch name is invalid, stop and suggest a
   valid one.
2. Base: `main` if the branch is `dev`; otherwise `dev`.
3. Template: the branch type (`feat/…` → `feat`) picks
   `.github/PULL_REQUEST_TEMPLATE/<type>.md`; `dev → main` uses `release.md`.
   Missing template → `.github/pull_request_template.md`.
4. Title: Conventional Commits, `type(scope): summary`, ≤ 72 chars, derived
   from the commits (`git log --oneline <base>..HEAD`).
5. Body: fill **every** section of the template from the diff
   (`git diff <base>...HEAD`) and from what was really run in this session.
   Sections that do not apply say `N/A` with a reason. Remove the HTML
   comments. "What was actually run" lists commands and results; unknown
   means "not run".
6. Write the body to a file in the scratchpad and run
   `gh pr create --base <base> --title "<title>" --body-file <file>`
   (`--draft` if the task is `[!]` or has a `Proposed` ADR).
7. No `Co-Authored-By`, no "Generated with" lines anywhere.
8. Print the PR URL.
